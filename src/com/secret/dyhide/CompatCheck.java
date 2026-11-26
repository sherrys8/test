package com.secret.dyhide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 首次激活 / 抖音版本变化后的「适配检测」（对齐隔壁 xfhide 的 ResolveSession + DexResolve）。
 *
 * dyhide 不使用 DexKit：所有 hook 点在 MainHook 里以「类名 + 方法名/参数个数」硬编码，
 * 再靠 try/catch 逐项安装。因此这里的「解析」是一次**兼容性自检**：用宿主 ClassLoader
 * 逐个验证这些被依赖的类 / 方法 / 字段在当前抖音 dex 里是否仍然存在。
 *   - 全部存在 → 「适配检测通过」→ 重启抖音生效
 *   - 有缺失   → 列出有问题的检查项 → 导出日志到 Download
 *
 * 触发：compat_stamp.txt 里的 stamp = versionName/versionCode/SCHEMA 与当前不一致时
 * （首次激活没有 stamp 也算不一致），在 MainActivity.onResume 弹一次提示（开始解析 / 稍后）。
 * 只有完整跑完检测才 commitStamp；「稍后」下次启动仍会提示。
 *
 * 注意：本工具链的 D8 对「嵌套匿名类」会内部 NPE，故所有 Runnable / OnClickListener
 * 一律用具名 static 内部类，不用匿名类。
 */
public final class CompatCheck {
    private static final String TAG = "XProHideContacts";
    private static final String TARGET_PKG = "com.ss.android.ugc.aweme";
    private static final String DATA_DIR = "/data/user/0/" + TARGET_PKG + "/dyhide";
    private static final String STAMP_FILE = DATA_DIR + "/compat_stamp.txt";
    /** 检查清单变更时 +1，强制重新解析（同 xfhide 的 DexResolve.SCHEMA）。 */
    private static final int SCHEMA = 1;

    private static volatile ClassLoader CL;
    private static volatile String VERSION_NAME = "?";
    private static volatile int VERSION_CODE = -1;
    /** 宿主 APK 路径（供 dex 特征扫描）。 */
    private static volatile String HOST_APK_PATH = null;
    /** 最近一次 dex 特征扫描的命中结果（后台线程产出，弹窗/导出使用）。 */
    private static volatile List<DexFeatureScanner.Hit> dexHits = null;
    private static volatile String dexScanError = null;
    /** v17：扫描结果与旧映射的差异摘要（供弹窗展示"哪个类变了"）。 */
    private static volatile String dexMapDiff = null;
    private static volatile boolean promptShownThisProcess = false;
    private static volatile boolean scanning = false;
    private static volatile AlertDialog dialog;
    private static java.lang.ref.WeakReference<Activity> LAST_ACT =
            new java.lang.ref.WeakReference<Activity>(null);

    private CompatCheck() {}

    /** 在 handleLoadPackage 末尾调用：记录版本、挂 MainActivity.onResume 触发检测。 */
    static void init(final XC_LoadPackage.LoadPackageParam lpp) {
        try {
            CL = lpp.classLoader;
            if (lpp.appInfo != null) {
                Object src = readField(lpp.appInfo, "sourceDir");
                if (src != null) HOST_APK_PATH = String.valueOf(src);
                Object vn = readField(lpp.appInfo, "versionName");
                Object vc = readField(lpp.appInfo, "versionCode");
                VERSION_NAME = vn == null ? "?" : String.valueOf(vn);
                try { VERSION_CODE = vc == null ? -1 : Integer.parseInt(String.valueOf(vc)); }
                catch (Throwable ignored) { VERSION_CODE = -1; }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat init ver failed " + t);
        }
        try {
            Class<?> ma = XposedHelpers.findClass("com.ss.android.ugc.aweme.main.MainActivity", lpp.classLoader);
            XposedBridge.hookAllMethods(ma, "onResume", new ResumeHook());
            XposedBridge.log(TAG + ": compat-check onResume hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat-check onResume hook failed " + t);
        }
    }

    /** MainActivity.onResume：首次进入且需要检测时弹提示。 */
    static final class ResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object o = param.thisObject;
                if (o instanceof Activity) onMainResume((Activity) o);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 检查清单
    // ------------------------------------------------------------------

    /** 一条依赖：若干候选类名（任一存在即可）+ 可选的方法名/参数个数。 */
    static final class Req {
        final String[] classes;
        final String method;      // null = 只校验类存在
        final int paramCount;     // -1 = 不限
        final String label;
        Req(String label, String method, int paramCount, String... classes) {
            this.label = label;
            this.method = method;
            this.paramCount = paramCount;
            this.classes = classes;
        }
        Req(String label, String... classes) {
            this(label, null, -1, classes);
        }
    }

    static final class Checkpoint {
        final String key;
        final String label;
        final List<Req> reqs;
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        Checkpoint(String key, String label, Req... reqs) {
            this.key = key;
            this.label = label;
            this.reqs = new ArrayList<Req>();
            for (Req r : reqs) this.reqs.add(r);
        }
    }

    private static List<Checkpoint> buildCheckpoints() {
        List<Checkpoint> out = new ArrayList<Checkpoint>();
        out.add(new Checkpoint("main", "主界面 Activity",
                req("MainActivity", "com.ss.android.ugc.aweme.main.MainActivity")));
        out.add(new Checkpoint("session", "消息会话列表",
                req("LJLILLLLZI(List,boolean) [41.3]", "LJLILLLLZI", 2,
                        "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.BaseSessionListAdapter"),
                req("setData(List) [41.3]", "setData", 1,
                        "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.BaseSessionListAdapter")));
        out.add(new Checkpoint("sessionItem", "会话项 / 未读状态",
                req("CommonSessionItemState",
                        "com.ss.android.ugc.aweme.im.service.rips.CommonSessionItemState")));
        out.add(new Checkpoint("user", "用户 / 联系人模型",
                req("User", "com.ss.android.ugc.aweme.profile.model.User"),
                req("IMContact", "com.ss.android.ugc.aweme.im.service.model.IMContact")));
        out.add(new Checkpoint("follow", "关注 / 粉丝列表",
                req("MF(List,*,boolean) [41.3]", "MF", 3,
                        "com.ss.android.ugc.aweme.following.ui.viewmodel.SelfFollowingRelationViewModel",
                        "com.ss.android.ugc.aweme.following.ui.viewmodel.OtherFollowingRelationViewModel"),
                req("OF(List,*,boolean) [41.3]", "OF", 3,
                        "com.ss.android.ugc.aweme.following.ui.viewmodel.SelfFollowerRelationViewModel",
                        "com.ss.android.ugc.aweme.following.ui.viewmodel.OtherFollowerRelationViewModel")));
        out.add(new Checkpoint("share", "分享面板联系人",
                req("NF(List) [41.3]", "NF", 1, "com.ss.android.ugc.aweme.im.share.IMSharePanelViewModel")));
        out.add(new Checkpoint("banner", "私信横幅拦截",
                req("LJ(*,*)", "LJ", 2,
                        "com.ss.android.ugc.aweme.im.sdk.notification.immessage.ImInnerPushManagerNew")));
        out.add(new Checkpoint("visitor", "主页访客列表",
                req("LJLILLLLZI(List,Map,boolean) [41.3]", "LJLILLLLZI", 3,
                        "com.ss.android.ugc.aweme.interaction.profilevisitor.adapter.ProfileVisitorAdapter")));
        out.add(new Checkpoint("interact", "互动消息列表",
                req("NotificationAdapterV2",
                        "com.ss.android.ugc.aweme.socialnotice.view.adapter.NotificationAdapterV2",
                        "com.ss.android.ugc.aweme.notification.adapter.NotificationAdapterV2")));
        out.add(new Checkpoint("generic", "通用列表适配器",
                req("setData(List) [41.3]", "setData", 1,
                        "com.ss.android.ugc.aweme.common.adapter.BaseAdapter")));
        out.add(new Checkpoint("cleanStorage", "聊天记录清理页",
                req("CleanStorageAdapter",
                        "com.ss.android.ugc.aweme.im.business.cleanstoragepage.view.CleanStorageAdapter")));
        out.add(new Checkpoint("invite", "一起看邀请面板",
                req("MateListSimpleSortVM.MF [41.3]", "MF", 4,
                        "com.ss.android.ugc.aweme.mate.matelist.list.simplesort.MateListSimpleSortVM")));
        out.add(new Checkpoint("profileEntry", "个人主页卡片入口",
                req("PandaCardEntriesServiceV3",
                        "com.ss.android.ugc.aweme.profile.panda.PandaCardEntriesServiceV3")));
        return out;
    }

    private static Req req(String label, String... classes) {
        return new Req(label, classes);
    }

    private static Req req(String label, String method, int paramCount, String... classes) {
        return new Req(label, method, paramCount, classes);
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 反射读字段（兼容 API stub jar 里 ApplicationInfo 没有 versionName 的情况）。 */
    private static Object readField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Class<?> load(String name) {
        if (CL == null) return null;
        try {
            return XposedHelpers.findClass(name, CL);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> c, String name, int paramCount) {
        Class<?> cur = c;
        while (cur != null) {
            Method[] ms = cur.getDeclaredMethods();
            for (Method m : ms) {
                if (name.equals(m.getName()) && (paramCount < 0 || m.getParameterTypes().length == paramCount)) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 返回 null 表示通过，否则返回失败描述。 */
    private static String verify(Req r) {
        Class<?> hit = null;
        StringBuilder tried = new StringBuilder();
        for (String cn : r.classes) {
            Class<?> c = load(cn);
            if (c != null) { hit = c; break; }
            if (tried.length() > 0) tried.append(" / ");
            tried.append(cn);
        }
        if (hit == null) {
            return "类缺失: " + tried;
        }
        if (r.method != null) {
            Method m = findMethod(hit, r.method, r.paramCount);
            if (m == null) {
                return "方法缺失: " + hit.getName() + "#" + r.method
                        + (r.paramCount >= 0 ? "(" + r.paramCount + "参)" : "");
            }
        }
        return null;
    }

    /** 后台逐项检测，完成后 commitStamp 并回调 onDone。 */
    private static void runChecks(final List<Checkpoint> checks, final ProgressBar bar,
                                  final TextView counter, final Runnable onDone) {
        new Thread(new CheckTask(checks, bar, counter, onDone), "dyhide-compat-check").start();
    }

    static final class CheckTask implements Runnable {
        final List<Checkpoint> checks;
        final ProgressBar bar;
        final TextView counter;
        final Runnable onDone;
        CheckTask(List<Checkpoint> checks, ProgressBar bar, TextView counter, Runnable onDone) {
            this.checks = checks; this.bar = bar; this.counter = counter; this.onDone = onDone;
        }
        @Override
        public void run() {
            int done = 0;
            for (Checkpoint cp : checks) {
                cp.ok = true;
                cp.detail.setLength(0);
                for (Req r : cp.reqs) {
                    String err = verify(r);
                    if (err != null) {
                        cp.ok = false;
                        if (cp.detail.length() > 0) cp.detail.append("\n");
                        cp.detail.append("• ").append(err);
                    } else if (cp.detail.length() == 0) {
                        cp.detail.append("通过: ").append(r.label);
                    }
                }
                done++;
                main(new ProgressUpdate(bar, counter, done, checks.size()));
                try { Thread.sleep(40); } catch (Throwable ignored) {}
            }
            // dex 特征扫描：解析宿主 dex，按稳定特征重新定位混淆类/方法
            runDexFeatureScan();
            commitStamp();
            main(onDone);
        }
    }

    /** 后台跑 dex 特征扫描，结果存到 dexHits / dexScanError，并落盘为动态映射。 */
    private static void runDexFeatureScan() {
        dexHits = null;
        dexScanError = null;
        dexMapDiff = null;
        String ver = VERSION_NAME + "(" + VERSION_CODE + ")";
        try {
            if (HOST_APK_PATH == null || HOST_APK_PATH.length() == 0) {
                dexScanError = "拿不到宿主 APK 路径";
                DexClassMap.recordScanError(ver, dexScanError);
                return;
            }
            java.util.List<DexFeatureScanner.DexSource> dexes =
                    DexFeatureScanner.loadDexFiles(HOST_APK_PATH);
            if (dexes == null || dexes.isEmpty()) {
                dexScanError = "dex 加载失败（0 个 dex）";
                DexClassMap.recordScanError(ver, dexScanError);
                return;
            }
            dexHits = DexFeatureScanner.scan(dexes, DexFeatureScanner.defaultFeatures());
            XposedBridge.log(TAG + ": dex feature scan done, hits=" + dexHits.size());
            // v17：落盘映射 + 记录与旧映射的 diff（下次启动 hook 用新类名）
            DexClassMap.load();
            dexMapDiff = DexClassMap.saveHits(dexHits, ver);
        } catch (Throwable t) {
            dexScanError = t.getClass().getSimpleName() + ": " + t.getMessage();
            DexClassMap.recordScanError(ver, dexScanError);
            XposedBridge.log(TAG + ": dex feature scan failed " + t);
        }
    }

    static final class ProgressUpdate implements Runnable {
        final ProgressBar bar; final TextView counter; final int done; final int total;
        ProgressUpdate(ProgressBar bar, TextView counter, int done, int total) {
            this.bar = bar; this.counter = counter; this.done = done; this.total = total;
        }
        @Override public void run() {
            try {
                if (bar != null) bar.setProgress(done);
                if (counter != null) counter.setText("正在检测 " + done + "/" + total);
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // stamp
    // ------------------------------------------------------------------

    private static String currentStamp() {
        return VERSION_NAME + "/" + VERSION_CODE + "/" + SCHEMA;
    }

    private static boolean isStale() {
        try {
            File f = new File(STAMP_FILE);
            if (!f.isFile()) return true;
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String line = r.readLine();
            r.close();
            return line == null || !line.trim().equals(currentStamp());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void commitStamp() {
        FileOutputStream out = null;
        try {
            File dir = new File(DATA_DIR);
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(DATA_DIR, "compat_stamp.tmp");
            out = new FileOutputStream(tmp);
            out.write((currentStamp() + "\n").getBytes("UTF-8"));
            out.close();
            out = null;
            File dst = new File(STAMP_FILE);
            if (dst.exists()) dst.delete();
            tmp.renameTo(dst);
            XposedBridge.log(TAG + ": compat stamp committed " + currentStamp());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat stamp commit failed " + t);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // 触发 & 弹窗
    // ------------------------------------------------------------------

    private static void onMainResume(Activity activity) {
        LAST_ACT = new java.lang.ref.WeakReference<Activity>(activity);
        // ApplicationInfo.versionName 在本机为 null，改用 PackageManager 精确取版本号
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(TARGET_PKG, 0);
            if (pi != null) {
                if (pi.versionName != null) VERSION_NAME = pi.versionName;
                VERSION_CODE = pi.versionCode;
            }
        } catch (Throwable ignored) {
        }
        if (promptShownThisProcess || scanning || dialog != null && dialog.isShowing()) return;
        if (!isStale()) return;
        promptShownThisProcess = true;
        main(new PromptTask(activity));
    }

    private static Handler mainHandler() {
        try { return new Handler(Looper.getMainLooper()); }
        catch (Throwable t) { return new Handler(); }
    }

    private static void main(Runnable r) {
        try { mainHandler().post(r); } catch (Throwable t) { r.run(); }
    }

    private static void dismiss() {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Throwable ignored) {}
        dialog = null;
    }

    private static LinearLayout root(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private static TextView title(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTextColor(0xFF3A2A35);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        // 樱粉紫渐变下划线，与 #dy 面板风格一致
        android.graphics.drawable.GradientDrawable line =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFFFF9EC0, 0xFFB896E8});
        line.setSize(dp(ctx, 46), dp(ctx, 3));
        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{
                                line, new android.graphics.drawable.ColorDrawable(0x00000000)});
        layers.setLayerSize(1, 1, 1);
        layers.setPadding(0, 0, 0, dp(ctx, 10));
        tv.setBackgroundDrawable(layers);
        return tv;
    }

    private static TextView body(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(color);
        tv.setLineSpacing(0, 1.25f);
        return tv;
    }

    private static Button btn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(14);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    private static void showDialog(Context ctx, View content) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            AlertDialog.Builder b = new AlertDialog.Builder(ctx);
            b.setView(content);
            dialog = b.create();
            dialog.setCancelable(false);
            dialog.show();
            // 与 #dy 面板一致的樱粉紫圆角窗口
            try {
                if (dialog.getWindow() != null) {
                    android.graphics.drawable.GradientDrawable winBg =
                            new android.graphics.drawable.GradientDrawable();
                    winBg.setColor(0xFFFAF5F8);
                    winBg.setCornerRadius(dp(ctx, 20));
                    dialog.getWindow().setBackgroundDrawable(winBg);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat dialog show failed " + t);
        }
    }

    /** 樱粉紫风格按钮：主按钮粉紫渐变，次按钮浅粉底。 */
    private static TextView prettyBtn(Context ctx, String text, boolean primary) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(null, primary ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF8A7080);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g;
        if (primary) {
            g = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFFF9EC0, 0xFFB896E8});
        } else {
            g = new android.graphics.drawable.GradientDrawable();
            g.setColor(0xFFF1E7ED);
        }
        g.setCornerRadius(dp(ctx, 11));
        b.setBackgroundDrawable(g);
        b.setPadding(dp(ctx, 8), dp(ctx, 11), dp(ctx, 8), dp(ctx, 11));
        return b;
    }

    /** 阶段 1：提示（开始解析 / 稍后） */
    static final class PromptTask implements Runnable {
        final Activity activity;
        PromptTask(Activity a) { this.activity = a; }
        @Override public void run() {
            Activity a = activity != null && !activity.isDestroyed() ? activity : LAST_ACT.get();
            if (a == null || a.isDestroyed()) return;
            LinearLayout box = root(a);
            box.addView(title(a, "版本适配检测"));
            TextView desc = body(a,
                    "检测到模块首次激活 或 抖音版本发生变化。\n"
                    + "当前版本 " + VERSION_NAME + " (" + VERSION_CODE + ")\n\n"
                    + "建议开始解析，检查本模块依赖的 hook 点是否仍然可用。\n"
                    + "全部通过即适配成功；有问题可导出日志反馈。", 0xFF8A7080);
            desc.setPadding(0, dp(a, 12), 0, dp(a, 8));
            box.addView(desc);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            TextView later = prettyBtn(a, "稍后", false);
            TextView start = prettyBtn(a, "开始解析", true);
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            half.leftMargin = dp(a, 5);
            start.setLayoutParams(half);
            LinearLayout.LayoutParams halfL = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            halfL.rightMargin = dp(a, 5);
            later.setLayoutParams(halfL);
            later.setOnClickListener(new PromptClick(false));
            start.setOnClickListener(new PromptClick(true));
            row.addView(later);
            row.addView(start);
            box.addView(row);
            showDialog(a, box);
        }
    }

    static final class PromptClick implements View.OnClickListener {
        final boolean start;
        PromptClick(boolean start) { this.start = start; }
        @Override public void onClick(View v) {
            dismiss();
            if (start) main(new PlayTask());
        }
    }

    /** 阶段 2：进度（不可取消） */
    static final class PlayTask implements Runnable {
        @Override public void run() {
            scanning = true;
            Activity a = LAST_ACT.get();
            if (a == null || a.isDestroyed()) return;
            final int total = buildCheckpoints().size();
            LinearLayout box = root(a);
            box.addView(title(a, "正在解析适配…"));
            TextView counter = body(a, "正在检测 0/" + total
                    + "（含 dex 特征扫描，首次约需 10-30 秒）", 0xFF555555);
            counter.setPadding(0, dp(a, 10), 0, dp(a, 8));
            box.addView(counter);
            ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(total);
            bar.setProgress(0);
            bar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(bar);
            showDialog(a, box);

            final List<Checkpoint> checks = buildCheckpoints();
            runChecks(checks, bar, counter, new PlayDone(checks));
        }
    }

    static final class PlayDone implements Runnable {
        final List<Checkpoint> checks;
        PlayDone(List<Checkpoint> checks) { this.checks = checks; }
        @Override public void run() { showDone(checks); }
    }

    /** 阶段 3：结果（通过 / 失败列表 + 导出日志 + 重启抖音） */
    private static void showDone(final List<Checkpoint> checks) {
        scanning = false;
        main(new DoneTask(checks));
    }

    /** dex 特征扫描结果视图：可滚动列表，显示每个语义名定位到的真实类。 */
    private static View dexScanResultView(Context ctx) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView head = body(ctx, "dex 特征扫描（自动适配）", 0xFF6A1B9A);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(0, dp(ctx, 6), 0, dp(ctx, 4));
        wrap.addView(head);

        if (dexScanError != null) {
            TextView err = body(ctx, "扫描失败: " + dexScanError, 0xFFC62828);
            err.setTextSize(12);
            wrap.addView(err);
            return wrap;
        }
        if (dexHits == null) {
            TextView wait = body(ctx, "未执行扫描", 0xFF999999);
            wait.setTextSize(12);
            wrap.addView(wait);
            return wrap;
        }

        ScrollView sv = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int okCount = 0;
        for (DexFeatureScanner.Hit h : dexHits) if (h.ok) okCount++;
        TextView summary = body(ctx, "命中 " + okCount + "/" + dexHits.size()
                + " 个目标（特征稳定即可自动找到新类名）", 0xFF555555);
        summary.setTextSize(12);
        summary.setPadding(0, 0, 0, dp(ctx, 4));
        list.addView(summary);

        for (DexFeatureScanner.Hit h : dexHits) {
            TextView t = new TextView(ctx);
            String line = (h.ok ? "✓ " : "✗ ") + h.key + " → "
                    + (h.ok ? h.javaName : "未找到");
            t.setText(line);
            t.setTextSize(11);
            t.setTextColor(h.ok ? 0xFF2E7D32 : 0xFFC62828);
            t.setPadding(dp(ctx, 4), dp(ctx, 2), 0, dp(ctx, 2));
            list.addView(t);
        }
        sv.addView(list);
        sv.setBackgroundColor(0xFFF7F3FA);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 150)));
        wrap.addView(sv);
        return wrap;
    }

    /** v17：hook 安装日志 + 类名变化 diff 视图（可滚动）。 */
    private static View adaptDiagView(Context ctx) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView head = body(ctx, "hook 安装诊断（本轮启动）", 0xFF1565C0);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4));
        wrap.addView(head);

        String hookLog = DexClassMap.readHookLog();
        ScrollView sv = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        if (hookLog == null || hookLog.length() == 0 || hookLog.contains("(无记录)")) {
            TextView t = body(ctx, "尚无 hook 日志（重启抖音后生成）", 0xFF999999);
            t.setTextSize(12);
            list.addView(t);
        } else {
            for (String line : hookLog.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                TextView tv = new TextView(ctx);
                tv.setText(t);
                tv.setTextSize(10);
                int color = t.startsWith("[ OK ]") ? 0xFF2E7D32
                        : (t.contains("src=DEXMAP") ? 0xFF6A1B9A : 0xFFC62828);
                tv.setTextColor(color);
                tv.setPadding(dp(ctx, 4), dp(ctx, 1), 0, dp(ctx, 1));
                list.addView(tv);
            }
        }
        // 类名变化 diff
        if (dexMapDiff != null && !"类名无变化".equals(dexMapDiff)) {
            TextView dh = body(ctx, "本次类名变化（已自动写入映射）", 0xFF6A1B9A);
            dh.setTextSize(12);
            dh.setPadding(0, dp(ctx, 6), 0, dp(ctx, 2));
            list.addView(dh);
            for (String line : dexMapDiff.split("\n")) {
                TextView tv = new TextView(ctx);
                tv.setText(line);
                tv.setTextSize(11);
                tv.setTextColor(0xFF6A1B9A);
                tv.setPadding(dp(ctx, 4), dp(ctx, 1), 0, dp(ctx, 1));
                list.addView(tv);
            }
        }
        sv.addView(list);
        sv.setBackgroundColor(0xFFF4F8FD);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 130)));
        wrap.addView(sv);
        return wrap;
    }

    static final class DoneTask implements Runnable {
        final List<Checkpoint> checks;
        DoneTask(List<Checkpoint> checks) { this.checks = checks; }
        @Override public void run() {
            Activity a = LAST_ACT.get();
            if (a == null || a.isDestroyed()) return;
            int failed = 0;
            for (Checkpoint cp : checks) if (!cp.ok) failed++;
            final boolean pass = failed == 0;

            dismiss();
            LinearLayout box = root(a);
            box.addView(title(a, pass ? "适配检测通过" : "发现适配问题"));
            TextView sum = body(a,
                    (pass ? "全部 " + checks.size() + " 项依赖均可用，适配成功。\n"
                          : "共 " + checks.size() + " 项，其中 " + failed
                            + " 项在新版本中找不到，可能导致对应功能失效。\n")
                    + "抖音版本 " + VERSION_NAME + " (" + VERSION_CODE + ")",
                    pass ? 0xFF2E7D32 : 0xFFC62828);
            sum.setPadding(0, dp(a, 10), 0, dp(a, 6));
            box.addView(sum);

            // dex 特征扫描结果：展示语义名 → 当前真实类名
            box.addView(dexScanResultView(a));

            // v17：hook 安装诊断 + 类名变化（成功失败都能看到，便于查是哪个类变了）
            box.addView(adaptDiagView(a));

            if (!pass) {
                ScrollView sv = new ScrollView(a);
                LinearLayout list = new LinearLayout(a);
                list.setOrientation(LinearLayout.VERTICAL);
                for (Checkpoint cp : checks) {
                    if (cp.ok) continue;
                    TextView t = new TextView(a);
                    t.setText("【" + cp.label + "】\n" + cp.detail);
                    t.setTextSize(12);
                    t.setTextColor(0xFFC62828);
                    t.setPadding(dp(a, 6), dp(a, 7), dp(a, 6), dp(a, 7));
                    list.addView(t);
                }
                sv.addView(list);
                sv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                sv.setBackgroundColor(0xFFFFF4F4);
                box.addView(sv);
            }

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            row.setPadding(0, dp(a, 8), 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

            TextView later = prettyBtn(a, "稍后", false);
            later.setLayoutParams(lp);
            later.setOnClickListener(new DoneClick(0, checks));
            row.addView(later);

            // v17：无论成功失败都提供日志导出（用户需要随时可查适配历史）
            TextView export = prettyBtn(a, "导出日志", false);
            lp.leftMargin = dp(a, 5);
            lp.rightMargin = dp(a, 5);
            export.setLayoutParams(lp);
            export.setOnClickListener(new DoneClick(1, checks));
            row.addView(export);

            TextView restart = prettyBtn(a, "重启抖音生效", true);
            restart.setLayoutParams(lp);
            restart.setOnClickListener(new DoneClick(2, checks));
            row.addView(restart);

            box.addView(row);
            showDialog(a, box);
        }
    }

    static final class DoneClick implements View.OnClickListener {
        final int what; // 0=稍后 1=导出 2=重启
        final List<Checkpoint> checks;
        DoneClick(int what, List<Checkpoint> checks) { this.what = what; this.checks = checks; }
        @Override public void onClick(View v) {
            if (what == 0) { dismiss(); return; }
            if (what == 2) { restartHost(); return; }
            Activity cur = LAST_ACT.get();
            new Thread(new ExportTask(checks, cur), "dyhide-export-log").start();
        }
    }

    static final class ExportTask implements Runnable {
        final List<Checkpoint> checks; final Activity ctx;
        ExportTask(List<Checkpoint> checks, Activity ctx) { this.checks = checks; this.ctx = ctx; }
        @Override public void run() {
            final String msg = exportLog(ctx, checks);
            main(new ExportToast(ctx, msg));
        }
    }

    static final class ExportToast implements Runnable {
        final Activity ctx; final String msg;
        ExportToast(Activity ctx, String msg) { this.ctx = ctx; this.msg = msg; }
        @Override public void run() {
            if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // 导出日志
    // ------------------------------------------------------------------

    private static String exportLog(Context ctx, List<Checkpoint> checks) {
        final String dirPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download";
        String name = "dyhide-适配检测-" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date()) + ".txt";
        StringBuilder sb = new StringBuilder();
        sb.append("dyhide 适配检测日志\n");
        sb.append("========================================\n");
        sb.append("抖音版本: ").append(VERSION_NAME).append(" (").append(VERSION_CODE).append(")\n");
        sb.append("模块 schema: ").append(SCHEMA).append("\n");
        sb.append("检测时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date())).append("\n");
        int failed = 0;
        for (Checkpoint cp : checks) if (!cp.ok) failed++;
        sb.append("结果: ").append(failed == 0 ? "通过" : ("失败 " + failed + "/" + checks.size())).append("\n");
        sb.append("========================================\n\n");
        for (Checkpoint cp : checks) {
            sb.append("[").append(cp.ok ? "OK" : "FAIL").append("] ").append(cp.key)
                    .append(" - ").append(cp.label).append("\n");
            if (cp.detail.length() > 0) sb.append(cp.detail).append("\n");
            sb.append("\n");
        }

        sb.append("---- dex 特征扫描 ----\n");
        if (dexScanError != null) {
            sb.append("扫描失败: ").append(dexScanError).append("\n");
        } else if (dexHits != null) {
            sb.append(DexFeatureScanner.format(dexHits));
        } else {
            sb.append("未执行扫描\n");
        }
        sb.append("\n");
        // v17：动态映射 + hook 安装日志 + 适配历史（定位"哪个类变了/哪个 hook 失败"）
        sb.append(DexClassMap.formatForExport());
        sb.append("\n");

        try {
            Process p = Runtime.getRuntime().exec("logcat -d -t 400 -s " + TAG + ":V");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            sb.append("---- logcat (最近 400 行) ----\n");
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 400) sb.append(line).append("\n");
            r.close();
        } catch (Throwable ignored) {
        }

        byte[] data;
        try { data = sb.toString().getBytes("UTF-8"); }
        catch (Throwable t) { data = sb.toString().getBytes(); }

        if (Build.VERSION.SDK_INT >= 29 && ctx != null) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(data);
                        os.flush();
                        os.close();
                        return "已导出到 Download/" + name;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": compat export MediaStore failed " + t);
            }
        }
        try {
            File d = new File(dirPath);
            if (!d.exists()) d.mkdirs();
            File f = new File(d, name);
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.close();
            return "已导出到 " + f.getAbsolutePath();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat export direct failed " + t);
            return "导出失败: " + t.getClass().getSimpleName();
        }
    }

    /** 一键重启：安排 alarm 拉起抖音，再杀掉当前进程。 */
    private static void restartHost() {
        Context ctx = LAST_ACT.get();
        if (ctx == null) ctx = MainHook.appContext();
        try {
            if (ctx != null) {
                Context app = ctx.getApplicationContext();
                Intent launch = app.getPackageManager().getLaunchIntentForPackage(TARGET_PKG);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                    android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                            app, 0x4F21, launch, flags);
                    android.app.AlarmManager am = (android.app.AlarmManager)
                            app.getSystemService(Context.ALARM_SERVICE);
                    if (am != null) {
                        long at = System.currentTimeMillis() + 1200L;
                        if (Build.VERSION.SDK_INT >= 23) am.setExact(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                        else am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat restart schedule failed " + t);
        }
        try { android.os.Process.killProcess(android.os.Process.myPid()); }
        catch (Throwable t) { System.exit(0); }
    }

    /** 供调试：从 #dy 面板主动重跑一次（清 stamp）。 */
    static void rerun() {
        try {
            File f = new File(STAMP_FILE);
            if (f.exists()) f.delete();
            promptShownThisProcess = false;
            Activity a = LAST_ACT.get();
            if (a != null && !a.isDestroyed()) main(new PromptTask(a));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": compat rerun failed " + t);
        }
    }
}
