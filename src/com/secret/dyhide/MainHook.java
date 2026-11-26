package com.secret.dyhide;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v2：消息页会话 + 关注/粉丝列表 + 分享面板联系人 + IM 搜索结果 过滤。
 *
 * hook 点（抖音 40.8.0 静态验证）：
 *   - BaseSessionListAdapter.LLD(List,Z) / LJLLLLLL(List)   消息页会话
 *   - Self/OtherFollowingRelationViewModel.HA(List,FollowingItemList,Z)  关注列表
 *   - Self/OtherFollowerRelationViewModel.KA(List,X.10js,Z)              粉丝列表
 *   - IMSharePanelViewModel.IA(List)                        分享面板联系人
 *   - common.adapter.BaseAdapter.LJLLI/LJLLLLLL/LJLZ/LJZ(List)  通用列表（搜索结果等）
 * 元素白名单：User / IMContact / CommonSessionItemState；X.0ozi 包装自动解包 LIZLLL。
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "XProHideContacts";
    private static final String TARGET_PKG = "com.ss.android.ugc.aweme";
    private static final String MY_PKG = "com.secret.dyhide";
    private static final String ACTION_UPDATE = "com.secret.dyhide.UPDATE";
    private static final String ACTION_REQ_FRIENDS = "com.secret.dyhide.REQ_FRIENDS";
    private static final String ACTION_FRIENDS = "com.secret.dyhide.FRIENDS";
    /** 调试用：从 adb 广播打开面板并打印异常堆栈 */
    private static final String ACTION_OPEN_PANEL = "com.secret.dyhide.OPEN_PANEL";
    private static final String ACTION_TOGGLE_TEMP = "com.secret.dyhide.TOGGLE_TEMP";
    /** v17：面板(子进程)请求主进程重跑适配检测（主进程才有 MainActivity 引用）。 */
    private static final String ACTION_RECHECK = "com.secret.dyhide.RECHECK";
    /** 采集到的好友：uid → 昵称（按出现顺序） */
    private static final java.util.LinkedHashMap<String, String> FRIENDS =
            new java.util.LinkedHashMap<>();
    private static volatile Context APP_CTX;
    private static long lastPublish = 0L;
    private static long lastRelDiag = 0L;
    /** 功能开关（key 见 InAppPanel.FEATURES）；null 表示全开（兼容旧配置） */
    private static volatile java.util.Set<String> features = new java.util.HashSet<>();
    /**
     * 批量提交期间挂起写盘：commitAll 里会连续调用多个 setter，每个 setter 都会
     * persistCurrent，把"尚未提交的旧 features"重复写盘；最后那次才带新列表。
     * 只要中间有任何一次落在后面，文件就被旧值覆盖 →「保存后重启全恢复」。
     */
    private static volatile boolean SUPPRESS_PERSIST = false;
    private static final java.util.Set<Context> REGISTERED_CTX =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Context, Boolean>());
    private static final String PROVIDER_URI = "content://com.secret.dyhide.config/";
    private static final String CFG_FILE = "xpro_hide.txt";
    private static final String FRIENDS_FILE = "xpro_friends.txt";
    /** 数据统一存放目录名：/data/user/0/<抖音>/dyhide/ */
    private static final String DATA_DIR_NAME = "dyhide";

    /** 统一数据目录：/data/user/0/com.ss.android.ugc.aweme/dyhide/ */
    private static File dataDir(Context ctx) {
        File dir;
        try {
            dir = new File(ctx.getApplicationInfo().dataDir, DATA_DIR_NAME);
        } catch (Throwable t) {
            dir = new File("/data/user/0/" + TARGET_PKG + "/" + DATA_DIR_NAME);
        }
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) {
        }
        return dir;
    }

    private static final String T_USER =
            "com.ss.android.ugc.aweme.profile.model.User";
    private static final String T_IMCONTACT =
            "com.ss.android.ugc.aweme.im.service.model.IMContact";
    private static final String T_SESSION_ITEM =
            "com.ss.android.ugc.aweme.im.service.rips.CommonSessionItemState";
    private static final String T_SESSION_INFO =
            "com.ss.android.ugc.aweme.im.sdk.chat.SessionInfo";
    private static final String T_IMUSER =
            "com.ss.android.ugc.aweme.im.service.model.IMUser";
    private static final Set<String> ALLOWED_TYPES = new HashSet<>(Arrays.asList(
            T_USER, T_IMCONTACT, T_SESSION_ITEM, T_IMUSER));
    /** 这些容器整体跳过匹配：评论区/信息流不在本模块职责内，且防止误伤 */
    private static final Set<String> DENY_TYPES = new HashSet<>(Arrays.asList(
            "com.ss.ugc.android.cachalot.core.model.FeedStructModel",
            "com.ss.android.ugc.aweme.comment.model.Comment"));

    private static volatile Set<String> uids = new HashSet<>();
    private static volatile Set<String> names = new HashSet<>();
    /** 消息页顶部头像条整栏隐藏，默认开；独立持久化，不受 features 兼容逻辑影响。 */
    private static volatile boolean topbarHidden = false;
    /** 主页访客记录隐藏，默认开。 */
    private static volatile boolean visitorHidden = false;
    /** 互动消息（点赞/访问等）隐藏，默认开。 */
    private static volatile boolean interactHidden = false;
    /** 禁止进入隐藏联系人主页/聊天，默认开。 */
    private static volatile boolean blockEnterHidden = false;
    /** 自定义删除「我」页顶部入口条：开关 + 名称/标识名单。 */
    private static volatile boolean profileEntryEnabled = false;
    private static volatile Set<String> profileEntryNames = new HashSet<>();
    /** v12：逐个隐藏——hook 扫描到的入口清单（标识 id:xxx / type:x → 显示名），供面板勾选 */
    private static final java.util.LinkedHashMap<String, String> discoveredProfileEntries =
            new java.util.LinkedHashMap<String, String>();
    /** 隐藏个人主页标签（作品/日常/收藏/喜欢/去过）：开关 + 名单（标识 tab:<type>）。 */
    private static volatile boolean profileTabEnabled = false;
    private static volatile Set<String> profileTabNames = new HashSet<>();
    /** hook LJJJJL 扫描到的 Tab 清单（标识 tab:<type> → 显示名），供面板勾选 */
    private static final java.util.LinkedHashMap<String, String> discoveredProfileTabs =
            new java.util.LinkedHashMap<String, String>();
    /**
     * 已知 Tab 预置表：抖音这 5 个 Tab 的 type 长期稳定，且 hook 与面板不在同一进程
     * （静态字段跨进程不共享），故预置它们保证弹窗永远能勾选。真机核对：0/38/16/100/46。
     */
    private static final java.util.LinkedHashMap<String, String> DEFAULT_PROFILE_TABS =
            new java.util.LinkedHashMap<String, String>();
    static {
        DEFAULT_PROFILE_TABS.put("tab:0", "作品");
        DEFAULT_PROFILE_TABS.put("tab:38", "日常");
        DEFAULT_PROFILE_TABS.put("tab:16", "收藏");
        DEFAULT_PROFILE_TABS.put("tab:100", "喜欢");
        DEFAULT_PROFILE_TABS.put("tab:46", "去过");
    }
    /** 已 hook 的 tabs 方法所属类，避免重复 hook。 */
    private static final java.util.Set<String> profileTabHookedClasses =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 隐藏「清理缓存 → 聊天记录空间管理」页里的隐藏联系人，默认关（独立开关）。 */
    private static volatile boolean cleanStorageHidden = false;
    /** 隐藏「我」页功能菜单里的「观看历史」入口，默认关。 */
    private static volatile boolean menuWatchHistoryHidden = false;
    /** 侧边栏入口自定义删除：总开关。 */
    private static volatile boolean sidebarHideEnabled = false;
    /** 左滑侧边栏要删除的入口名称名单（用户自定义，默认空）。 */
    private static volatile Set<String> sidebarHideNames = new HashSet<>();
    /** 右滑侧边栏自定义删除：开关 + 名单。 */
    private static volatile boolean sidebarHideRightEnabled = false;
    private static volatile Set<String> sidebarHideRightNames = new HashSet<>();
    /** 已隐藏的菜单项 → 原 visibility（关闭开关时恢复）。 */
    private static final java.util.Map<android.view.View, int[]> SIDEBAR_HIDDEN =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, int[]>());
    private static final java.util.Set<String> PROBE2 =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<String, Boolean>());

    /** 侧边栏内的网格容器缓存（只对它们做「布局后纠正」，避免全局开销）。 */
    private static final java.util.Set<android.widget.GridLayout> SIDEBAR_GRIDS =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<android.widget.GridLayout, Boolean>());
    /** 被从网格中真正移除的项：View → {parent, index}（关闭开关时插回）。 */
    private static final java.util.Map<android.view.View, Object[]> SIDEBAR_REMOVED =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, Object[]>());
    /** 持久化的总开关（写盘用）；enabled 是叠加"临时关闭"后的实际生效值。 */
    private static volatile boolean enabledSaved = false;
    private static volatile boolean enabled = false;
    /** 会话内临时恢复：长按消息页标题切换，重启抖音自动失效，不写进配置文件。 */
    private static volatile boolean tempPaused = false;
    /** 临时显示功能自身的开关（面板「恢复」组），与会话临时态分开持久化。 */
    private static volatile boolean tempDisplayEnabled = true;
    /** 长按判定阈值（毫秒）与两条提示词，留空用内置文案。 */
    private static volatile int longPressMs = 500;
    private static volatile String tempTipOn = "";
    private static volatile String tempTipOff = "";
    /** v10 新功能：隐藏联系人消息提醒。
     *  应用内横幅：直接屏蔽不弹出；消息页标题：命中隐藏联系人消息时显示红点。 */
    private static volatile boolean notifyMaskEnabled = true;
    private static volatile boolean installed = false;
    private static volatile boolean transportInited = false;

    /** mutate 过滤前备份的原始 List 内容（按 List 引用身份），开关关闭时据此恢复。 */
    private static final java.util.Map<List, java.util.List> MUTATED_BACKUP =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<List, java.util.List>());
    private static long lastRelSearchDiag = 0L;
    private static long lastEnterDiag = 0L;
    private static long lastActivityDiag = 0L;

    /** 已折叠行 → 原始宽高（应对 RecyclerView 复用） */
    private static final Map<android.view.View, int[]> HIDDEN_ROWS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.view.View, int[]>());
    /** 消息页顶部头像条整栏 → 原始可见性/布局参数（topbar 开关关闭时恢复） */
    private static final java.util.Map<android.view.View, int[]> TOPBAR_BARS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.view.View, int[]>());

    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();
    private static final Map<String, Field[]> WRAPPER_FIELDS = new HashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();
    /** key=item类名 → 是否属于白名单类型（含子类），按 item 的 classloader 解析 */
    private static final Map<String, Boolean> TYPE_OK_CACHE = new HashMap<>();
    /** 调试日志节流：label → 上次输出时间 */
    private static final Map<String, Long> DEBUG_THROTTLE = new HashMap<>();
    /** 已记录过的适配器类（setAdapter 去重） */
    private static final Set<String> ADAPTER_SEEN = new HashSet<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!TARGET_PKG.equals(lpp.packageName)) return;
        if (installed) return;
        installed = true;

        // v17 阶段二：先读 dex 扫描落盘的类名映射（供下面各 hook 点动态取用）
        try { DexClassMap.load(); }
        catch (Throwable t) { XposedBridge.log(TAG + ": dexmap load failed " + t); }
        loadPersistedConfig();
        loadPersistedFriends();
        installSessionHook(lpp.classLoader);
        installGenericHooks(lpp.classLoader);
        installVisitorHook(lpp.classLoader);
        installInteractNoticeHook(lpp.classLoader);   // v7.2 需求1：互动消息按 uid 隐藏
        // ===== v8.1 说明：观看历史/经常访问/访问记录（Lynx 页面）无法在客户端隐藏数据 =====
        // 实测结论：该页是 AnnieX+Lynx（Canvas 渲染，无 View 节点），网络数据在 native 层，
        // Java 层无任何数据入口（fetch/setHttpBody/getHttpBody/invoke 全未调用）。
        // dyhelper 同名功能实测也只是「拦截点击进入主页」= 本模块已有的 blockEnter，故无需重复实现。
        // installLynxHistoryFilter(lpp.classLoader);   // 诊断代码保留，不启用
        // installVisitReportSpy(lpp.classLoader);    // 无效：访问记录不走埋点
        // installUrlSpy(lpp.classLoader);            // v8.2 抓包结论：无独立访问上报接口（服务端记录）
        installSidebarEntryHide(lpp.classLoader);
        installProfileEntryHide(lpp.classLoader);
        installProfileTabHide(lpp.classLoader);   // 个人主页标签逐个隐藏（作品/日常/收藏/喜欢/去过）
        installMessageTitleGesture(lpp.classLoader);
        installCleanStorageFilter(lpp.classLoader);   // v8.9 聊天记录空间管理页按昵称隐藏行     // v8.3 隐藏「我」页侧边菜单的「观看历史」入口
        // installLynxCbDiag(lpp.classLoader);          // JSB 回调诊断，不启用
        // installLynxHttpBodyFilter(lpp.classLoader);  // httbody 过滤（未命中），不启用
        installLynxHttpBodyFilter(lpp.classLoader);   // v8.1 观看历史/经常访问/访问记录（Lynx 响应过滤）
        installStartActivityGuard(lpp.classLoader);
        installActivityEnterGuard(lpp.classLoader);   // v7.2 需求3：onCreate/onNewIntent 兜底拦截（SmartRouter 路由不走 startActivity）
        installAdapterSpy(lpp.classLoader);   // 诊断互动消息页真实 adapter 类名
        installDmTrigger(lpp.classLoader);
        installNotifyMaskHooks(lpp.classLoader);   // v10：横幅屏蔽 + 系统通知栏改写
        installContextHook(lpp.classLoader);
        // v17 阶段二：把本轮所有 hook 安装结果落盘（供检测弹窗/日志导出诊断）
        try { DexClassMap.flushHooks(hostVersion(lpp)); }
        catch (Throwable t) { XposedBridge.log(TAG + ": hook log flush failed " + t); }

        // v11：首次激活 / 版本变化后的适配检测（对齐 xfhide 的解析流程）
        try { CompatCheck.init(lpp); }
        catch (Throwable t) { XposedBridge.log(TAG + ": compat init failed " + t); }
    }

    /** 取宿主版本字符串，供适配日志标注（运行时反射，兼容 API stub 无 versionName 字段）。 */
    private static String hostVersion(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            if (lpp.appInfo != null) {
                Object vn = readFieldCompat(lpp.appInfo, "versionName");
                Object vc = readFieldCompat(lpp.appInfo, "versionCode");
                return (vn == null ? "?" : String.valueOf(vn)) + "("
                        + (vc == null ? "?" : String.valueOf(vc)) + ")";
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 运行时反射读字段（遍历父类），兼容 stub jar 里缺失的字段。 */
    private static Object readFieldCompat(Object obj, String name) {
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

    /** 供 CompatCheck 在重启时取 Context（APP_CTX 由 Application.attach hook 赋值）。 */
    static Context appContext() { return APP_CTX; }

    /** v17：面板（可能在子进程）请求重跑适配检测：发广播给抖音主进程的 receiver。 */
    static void requestRecheck(Context ctx) {
        Context c = ctx != null ? ctx : APP_CTX;
        if (c == null) return;
        try {
            Intent in = new Intent(ACTION_RECHECK);
            in.setPackage(TARGET_PKG);
            in.addFlags(0x01000000 /* FLAG_RECEIVER_INCLUDE_BACKGROUND */);
            c.sendBroadcast(in);
            XposedBridge.log(TAG + ": recheck broadcast sent");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": recheck broadcast failed " + t);
        }
    }

    /** 抖音内触发器：任意输入框输入 #dy 打开设置面板 */
    private static void installDmTrigger(ClassLoader cl) {
        // EditText 构造时即挂 TextWatcher（覆盖重写 onAttachedToWindow 的输入框）
        try {
            Class<?> etCls = XposedHelpers.findClass("android.widget.EditText", cl);
            XposedBridge.hookAllConstructors(etCls, new EditTextCtorHook());
            XposedBridge.log(TAG + " edittext ctor hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " edittext ctor hook failed: " + t);
        }
        try {
            Class<?> view = XposedHelpers.findClass("android.view.View", cl);
            XposedHelpers.findAndHookMethod(view, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object o = param.thisObject;
                        if (!(o instanceof android.widget.EditText)) return;
                        android.widget.EditText et = (android.widget.EditText) o;
                        synchronized (WATCHED) {
                            if (WATCHED.containsKey(et)) return;
                            WATCHED.put(et, Boolean.TRUE);
                        }
                        et.addTextChangedListener(new DmWatcher(et));
                        XposedBridge.log(TAG + " watcher attached: " + et.getClass().getName());
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": #dy trigger installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": #dy trigger failed: " + t);
        }
    }

    private static class EditTextCtorHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                android.widget.EditText et = (android.widget.EditText) param.thisObject;
                synchronized (WATCHED) {
                    if (WATCHED.containsKey(et)) return;
                    WATCHED.put(et, Boolean.TRUE);
                }
                et.addTextChangedListener(new DmWatcher(et));
            } catch (Throwable ignored) {
            }
        }
    }

    private static final Map<android.widget.EditText, Boolean> WATCHED =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.widget.EditText, Boolean>());

    private static class DmWatcher implements android.text.TextWatcher {
        private final android.widget.EditText et;

        DmWatcher(android.widget.EditText et) {
            this.et = et;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            try {
                if (s == null) return;
                String t = s.toString().trim();
                // 中文输入法下 '#' 常被输入成全角 `＃`(U+FF03)，统一成半角再比较
                t = t.replace('\uFF03', '#').replace('\uFF04', '$');
                if (t.startsWith("#")) {
                    XposedBridge.log(TAG + " typed: '" + t + "' cls=" + et.getClass().getName());
                }
                if (!"#dy".equalsIgnoreCase(t)) return;
                et.setText("");
                XposedBridge.log(TAG + ": opening panel from #dy");
                try {
                    InAppPanel.show(et.getContext());
                } catch (Throwable panelError) {
                    XposedBridge.log(TAG + ": panel open FAILED: "
                            + android.util.Log.getStackTraceString(panelError));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": typed hook error: "
                        + android.util.Log.getStackTraceString(t));
            }
        }
    }

    /** 诊断：监听 LayoutInflater.inflate，输出 live/share 相关上下文的布局加载记录。 */
    private static void installInflateSpy(ClassLoader cl) {
        try {
            Class<?> inf = XposedHelpers.findClass("android.view.LayoutInflater", cl);
            Class<?> vg = XposedHelpers.findClass("android.view.ViewGroup", cl);
            XposedHelpers.findAndHookMethod(inf, "inflate",
                    int.class, vg, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object ctx = XposedHelpers.getObjectField(param.thisObject, "mContext");
                                if (ctx == null) return;
                                String cn = ctx.getClass().getName().toLowerCase();
                                if (!cn.contains("live") && !cn.contains("share")
                                        && !cn.contains("picker")) return;
                                int id = (Integer) param.args[0];
                                String key = cn + "#" + id;
                                synchronized (ADAPTER_SEEN) {
                                    if (!ADAPTER_SEEN.add(key)) return;
                                }
                                String name = Integer.toString(id);
                                try {
                                    android.content.Context c = (android.content.Context) ctx;
                                    name = c.getResources().getResourceEntryName(id);
                                } catch (Throwable ignored) {
                                }
                                StringBuilder sb = new StringBuilder("inflate ").append(name)
                                        .append(" ctx=").append(ctx.getClass().getName())
                                        .append(" stack:");
                                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                int shown = 0;
                                for (StackTraceElement e : st) {
                                    String cn2 = e.getClassName();
                                    if (cn2.startsWith("de.robv") || cn2.startsWith("java.")
                                            || cn2.startsWith("android.") || cn2.startsWith("dalvik.")
                                            || cn2.startsWith("com.secret.dyhide")
                                            || cn2.length() <= 2) continue;
                                    sb.append(' ').append(cn2).append('#').append(e.getMethodName());
                                    if (++shown >= 5) break;
                                }
                                XposedBridge.log(TAG + ": " + sb);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": inflate spy installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": inflate spy failed: " + t);
        }
    }
    /** 诊断：监听 Dialog.show，输出弹窗类名（用于定位自定义分享面板）。 */
    private static void installDialogSpy(ClassLoader cl) {
        try {
            Class<?> dlg = XposedHelpers.findClass("android.app.Dialog", cl);
            XposedHelpers.findAndHookMethod(dlg, "show", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String cn = param.thisObject.getClass().getName();
                        synchronized (ADAPTER_SEEN) {
                            if (!ADAPTER_SEEN.add("dlg:" + cn)) return;
                        }
                        XposedBridge.log(TAG + ": dialog " + cn);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + ": dialog spy installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dialog spy failed: " + t);
        }
    }
    /**
     * v8.1 诊断：Lynx JSB 回调通道（CallbackImpl.invokeCallback/invoke）。
     * 网络数据在此从 Java 回传给 JS，是唯一可能修改数据的 Java 入口。
     */
    private static void installLynxCbDiag(ClassLoader cl) {
        try {
            Class<?> cb = cl.loadClass("com.lynx.jsbridge.CallbackImpl");
            XposedBridge.hookAllMethods(cb, "invokeCallback", new LynxCbFilterHook());
            XposedBridge.hookAllMethods(cb, "invoke", new LynxCbFilterHook());
            // 诊断：JavaOnlyMap / JavaOnlyArray 内部字段名
            String[] bridges = {"com.lynx.react.bridge.JavaOnlyMap",
                    "com.lynx.react.bridge.JavaOnlyArray"};
            for (String bn : bridges) {
                try {
                    Class<?> bc = cl.loadClass(bn);
                    for (java.lang.reflect.Field fd : bc.getDeclaredFields()) {
                        XposedBridge.log(TAG + ": lynx-bridge-field " + bc.getSimpleName()
                                + "." + fd.getName() + " : " + fd.getType().getSimpleName());
                    }
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": lynx-cb filter hook ok");
            // 万能入口诊断：LynxMethodWrapper（所有 JSB 方法调用必经）
            String[] mws = {"com.lynx.jsbridge.LynxMethodWrapper",
                    "com.lynx.jsbridge.LynxModuleWrapper"};
            for (String mwName : mws) {
                try {
                    Class<?> mw = cl.loadClass(mwName);
                    for (java.lang.reflect.Method mm : mw.getDeclaredMethods()) {
                        StringBuilder msb = new StringBuilder();
                        for (Class<?> pt : mm.getParameterTypes()) msb.append(pt.getSimpleName()).append(',');
                        XposedBridge.log(TAG + ": lynx-mw-m " + mw.getSimpleName()
                                + "#" + mm.getName() + "(" + msb + ")");
                    }
                    XposedBridge.hookAllMethods(mw, "invoke", new LynxMwHook());
                    XposedBridge.log(TAG + ": lynx-mw hook " + mw.getSimpleName());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": lynx-mw skip " + mwName);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lynx-cb diag skip " + t.getClass().getSimpleName());
        }
    }

    /** 万能入口监视：JSB 方法调用参数里找隐藏 uid */
    private static class LynxMwHook extends XC_MethodHook {
        private long last = 0L;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                int total = 0;
                for (Object a : param.args) {
                    if (a instanceof Object[]) {
                        Object[] arr = (Object[]) a;
                        for (int i = 0; i < arr.length; i++) {
                            Object e = arr[i];
                            if (e == null) continue;
                            if (lynxContainsHiddenUid(e, 0)) {
                                total += 1;
                            }
                            pruneLynxNode(e, 0);
                        }
                    } else if (a != null && lynxContainsHiddenUid(a, 0)) {
                        total += 1;
                        pruneLynxNode(a, 0);
                    }
                }
                if (total > 0) {
                    long now = System.currentTimeMillis();
                    if (now - last > 800) {
                        last = now;
                        XposedBridge.log(TAG + ": lynx-mw hidden-uid " + total
                                + " m=" + param.method.getName());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** JSB 回调过滤：在数据交给 JS 前移除含隐藏 uid 的列表元素 */
    private static class LynxCbFilterHook extends XC_MethodHook {
        private long lastLog = 0L;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                int total = 0;
                for (Object a : param.args) {
                    if (a instanceof Object[]) {
                        for (Object e : (Object[]) a) {
                            total += pruneLynxNode(e, 0);
                        }
                    } else {
                        total += pruneLynxNode(a, 0);
                    }
                }
                if (total > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 1000) {
                        lastLog = now;
                        XposedBridge.log(TAG + ": lynx-cb pruned " + total + " item(s)");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 递归遍历 Lynx 结构，删除数组中含隐藏 uid 的元素 */
    private static int pruneLynxNode(Object node, int depth) {
        if (node == null || depth > 12) return 0;
        int n = 0;
        String cn = node.getClass().getName();
        if (cn.endsWith("JavaOnlyMap")) {
            java.util.Map<String, Object> m = lynxBackingMap(node);
            if (m == null) return 0;
            for (Object v : new java.util.ArrayList<>(m.values())) {
                n += pruneLynxNode(v, depth + 1);
            }
        } else if (cn.endsWith("JavaOnlyArray")) {
            java.util.List<Object> list = lynxBackingList(node);
            if (list == null) return 0;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object el;
                try {
                    el = list.get(i);
                } catch (Throwable t) {
                    continue;
                }
                if (el != null && lynxContainsHiddenUid(el, 0)) {
                    try {
                        list.remove(i);
                        n++;
                    } catch (Throwable ignored) {
                    }
                } else {
                    n += pruneLynxNode(el, depth + 1);
                }
            }
        }
        return n;
    }

    /** Lynx 节点（Map/Array/标量）里是否有值精确等于隐藏 uid */
    private static boolean lynxContainsHiddenUid(Object node, int depth) {
        if (node == null || depth > 12) return false;
        String cn = node.getClass().getName();
        if (cn.endsWith("JavaOnlyMap")) {
            java.util.Map<String, Object> m = lynxBackingMap(node);
            if (m == null) return false;
            for (Object v : new java.util.ArrayList<>(m.values())) {
                if (v instanceof String || v instanceof Number) {
                    if (isHiddenUid(String.valueOf(v).trim())) return true;
                } else if (lynxContainsHiddenUid(v, depth + 1)) {
                    return true;
                }
            }
        } else if (cn.endsWith("JavaOnlyArray")) {
            java.util.List<Object> list = lynxBackingList(node);
            if (list == null) return false;
            for (Object v : new java.util.ArrayList<>(list)) {
                if (v instanceof String || v instanceof Number) {
                    if (isHiddenUid(String.valueOf(v).trim())) return true;
                } else if (lynxContainsHiddenUid(v, depth + 1)) {
                    return true;
                }
            }
        } else if (node instanceof String) {
            return isHiddenUid(((String) node).trim());
        } else if (node instanceof Number) {
            return isHiddenUid(String.valueOf(node));
        }
        return false;
    }

    /** 反射取 JavaOnlyMap 内部 map（兼容多种字段名） */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> lynxBackingMap(Object node) {
        String[] names = {"mBackingMap", "mMap", "mHashMap", "map"};
        for (String nm : names) {
            Object v = getFieldDeep(node, nm);
            if (v instanceof java.util.Map) return (java.util.Map<String, Object>) v;
        }
        return null;
    }

    /** 反射取 JavaOnlyArray 内部 list */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> lynxBackingList(Object node) {
        String[] names = {"mBackingList", "mList", "mArrayList", "list"};
        for (String nm : names) {
            Object v = getFieldDeep(node, nm);
            if (v instanceof java.util.List) return (java.util.List<Object>) v;
        }
        return null;
    }

    /** dump JSB 回调参数的类型与内容（找用户列表数据） */
    private static class LynxCbHook extends XC_MethodHook {
        private long last = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                long now = System.currentTimeMillis();
                if (now - last < 600) return;
                last = now;
                StringBuilder sb = new StringBuilder(param.method.getName());
                for (Object a : param.args) {
                    if (a == null) {
                        sb.append(" | null");
                        continue;
                    }
                    if (a instanceof Object[]) {
                        Object[] arr = (Object[]) a;
                        sb.append(" | array[").append(arr.length).append(']');
                        for (int i = 0; i < arr.length && i < 3; i++) {
                            Object e = arr[i];
                            if (e == null) {
                                sb.append(" null,");
                                continue;
                            }
                            String es = String.valueOf(e);
                            sb.append(' ').append(e.getClass().getSimpleName()).append(':')
                                    .append(es.length() > 200 ? es.substring(0, 200) : es);
                        }
                    } else {
                        String es = String.valueOf(a);
                        sb.append(" | ").append(a.getClass().getSimpleName()).append(':')
                                .append(es.length() > 250 ? es.substring(0, 250) : es);
                    }
                }
                XposedBridge.log(TAG + ": lynx-cb " + sb);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v8.1 实现：观看历史 /「我经常访问的人」/「我的访问记录」（Lynx 页面）按 uid 隐藏。
     * 数据流确认：JS fetch → Lynx network JSB → HttpResponse(mHttpBody byte[], mUrl)。
     * hook HttpResponse.setHttpBody(byte[])，对含 nickname+uid 的 JSON 移除隐藏用户对象。
     * 由「隐藏主页访客记录」(visitorHidden) 开关控制。
     */
    private static void installLynxHttpBodyFilter(ClassLoader cl) {
        try {
            Class<?> resp = cl.loadClass("com.lynx.jsbridge.network.HttpResponse");
            XposedBridge.hookAllMethods(resp, "setHttpBody", new LynxBodyFilterHook());
            XposedBridge.hookAllMethods(resp, "getHttpBody", new LynxGetBodyHook());
            java.lang.reflect.Constructor<?>[] cs = resp.getDeclaredConstructors();
            for (java.lang.reflect.Constructor<?> cc : cs) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> pt : cc.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": lynx-resp-ctor (" + sb + ")");
            }
            XposedBridge.log(TAG + ": lynx-body-filter hook setHttpBody+getHttpBody");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lynx-body-filter skip " + t.getClass().getSimpleName());
        }
    }

    /** Lynx 响应体读取点过滤（getHttpBody 返回过滤后的字节） */
    private static class LynxGetBodyHook extends XC_MethodHook {
        private long lastDump = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object r = param.getResult();
                if (!(r instanceof byte[])) return;
                byte[] b = (byte[]) r;
                if (b.length < 40 || b.length > 2000000) return;
                String str;
                try {
                    str = new String(b, "UTF-8");
                } catch (Throwable t) {
                    return;
                }
                String t = str.trim();
                if (!t.startsWith("{") && !t.startsWith("[")) return;
                // 诊断：dump 结构片段（节流 3s，仅在开启隐藏时）
                if (enabled && visitorHidden) {
                    long now = System.currentTimeMillis();
                    if (now - lastDump > 3000) {
                        lastDump = now;
                        String head = t.length() > 400 ? t.substring(0, 400) : t;
                        XposedBridge.log(TAG + ": lynx-body-sample len=" + b.length + " " + head);
                    }
                }
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                Object root;
                try {
                    root = new org.json.JSONTokener(t).nextValue();
                } catch (Throwable tt) {
                    return;
                }
                int removed = pruneHiddenUsers(root);
                if (removed > 0) {
                    param.setResult(root.toString().getBytes("UTF-8"));
                    String url = "";
                    try {
                        url = String.valueOf(XposedHelpers.callMethod(param.thisObject, "getUrl"));
                    } catch (Throwable ignored) {
                    }
                    XposedBridge.log(TAG + ": lynx-body pruned " + removed
                            + " item(s) url=" + shortUrl(url));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Lynx 响应体过滤：JSON 中含隐藏用户的数组元素整项移除 */
    private static class LynxBodyFilterHook extends XC_MethodHook {
        private long lastLog = 0L;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                Object a0 = param.args[0];
                if (!(a0 instanceof byte[])) return;
                byte[] bytes = (byte[]) a0;
                if (bytes.length < 60 || bytes.length > 2000000) return;
                String json = new String(bytes, "UTF-8");
                if (!json.contains("nickname") || !json.contains("uid")) return;
                Object root;
                try {
                    root = new org.json.JSONTokener(json).nextValue();
                } catch (Throwable t) {
                    return;
                }
                if (!(root instanceof org.json.JSONObject) && !(root instanceof org.json.JSONArray)) {
                    return;
                }
                int removed = pruneHiddenUsers(root);
                String url = "";
                try {
                    url = String.valueOf(XposedHelpers.callMethod(param.thisObject, "getUrl"));
                } catch (Throwable ignored) {
                }
                if (removed > 0) {
                    param.args[0] = root.toString().getBytes("UTF-8");
                    XposedBridge.log(TAG + ": lynx-body pruned " + removed
                            + " item(s) url=" + shortUrl(url));
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 8000) {
                        lastLog = now;
                        XposedBridge.log(TAG + ": lynx-body pass len=" + bytes.length
                                + " url=" + shortUrl(url));
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        try {
            int q = url.indexOf('?');
            String base = q > 0 ? url.substring(0, q) : url;
            return base.length() > 90 ? base.substring(0, 90) : base;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 被隐藏的「我」页入口项 → 原 visibility（关闭开关时恢复） */
    private static final java.util.Map<android.view.View, Integer> PROFILE_ENTRY_HIDDEN =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, Integer>());

    private static boolean profileScanStarted = false;
    /** 临时开关：停用整排 GONE，单独验证数据层过滤 */
    private static boolean VIEW_HIDE_OFF = false;

    // ===== v9.6：长按消息页标题「消息」临时关闭/恢复隐藏 =====
    private static final java.util.Set<android.view.View> GESTURE_TITLES =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<android.view.View, Boolean>());
    private static volatile android.os.Handler TITLE_HANDLER;
    private static volatile java.lang.ref.WeakReference<android.app.Activity> LAST_ACTIVITY =
            new java.lang.ref.WeakReference<android.app.Activity>(null);
    private static volatile boolean messageTitleAttached = false;
    private static volatile boolean titlePollRunning = false;
    /** v10.5：隐藏联系人来消息后，「消息」标题右侧红点是否待显示 */
    private static volatile boolean hiddenDotPending = false;
    /** 复用的红点 Drawable，避免重复 new；也用于判断标题上是否已是我们的红点。 */
    private static volatile android.graphics.drawable.Drawable cachedDot = null;
    /** 会话未读数上次值（uid -> unread），用于判断「新消息到达」。 */
    private static final Map<String, Integer> dotLastUnread = new HashMap<>();

    /**
     * 从会话列表 item 读未读数。
     * 抖音 40.8.0 结构：SingleSessionItemState(继承 IMBaseSessionItemState) 有字段
     * unreadState = LX/0oPx，其 toString 为 "UnreadState(unreadCount=<LIZ>)"。
     * 未读数就是 0oPx.LIZ(int)。读不到一律返回 -1，绝不用时间兜底（会误报）。
     */
    private static int sessionItemUnread(Object item) {
        if (item == null) return -1;
        // getFieldQuiet 会遍历父类，unreadState 在父类 IMBaseSessionItemState 上
        Object us = getFieldQuiet(item, "unreadState");
        if (us == null) {
            // 兜底：深度找类型为 UnreadState(类名含 0oPx) 的字段对象
            us = findUnreadState(item, item.getClass(), 0);
        }
        if (us == null) return -1;
        try {
            Object v = getFieldQuiet(us, "LIZ"); // unreadCount
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 深度递归找 unreadState(LX/0oPx) 对象，找不到返回 null。 */
    private static Object findUnreadState(Object holder, Class<?> c, int depth) {
        if (depth > 5 || holder == null || c == null || c.isArray() || c.isPrimitive()) return null;
        if (DENY_TYPES.contains(c.getName())) return null;
        if (c.getName().contains("0oPx") || c.getName().endsWith("UnreadState")) return holder;
        Field[] fields = getFieldsCached(c);
        for (Field f : fields) {
            try {
                Object v = f.get(holder);
                if (v == null || v == holder) continue;
                if ("unreadState".equals(f.getName())
                        || v.getClass().getName().contains("0oPx")) return v;
                Object hit = findUnreadState(v, v.getClass(), depth + 1);
                if (hit != null) return hit;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 会话列表刷新时调用：隐藏会话未读数真实增加才点亮「消息」标题红点。
     * 读不到未读数一律不触发（不再用时间节流兜底，那是误报根源）。
     * 首次见到某 uid 只记基线不触发，避免进入消息页时因历史未读误亮。
     */
    private static void checkSessionDot(Object item) {
        try {
            if (!notifyMaskActive()) return;
            String uid = resolveUid(item);
            if (uid == null || !isHiddenUid(uid)) return;
            int unread = sessionItemUnread(item);
            if (unread < 0) {
                XposedBridge.log(TAG + ": session-dot uid=" + uid
                        + " unread=N/A skip(type=" + (item == null ? "?" : item.getClass().getName()) + ")");
                return;
            }
            Integer prev = dotLastUnread.get(uid);
            boolean fire = prev != null && unread > prev;
            dotLastUnread.put(uid, unread);
            XposedBridge.log(TAG + ": session-dot uid=" + uid + " unread=" + unread
                    + " prev=" + prev + " fire=" + fire);
            if (fire) signalHiddenMessageDot("session:" + uid);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": session-dot err " + t);
        }
    }

    private static void installMessageTitleGesture(ClassLoader cl) {
        try {
            Class<?> ma = cl.loadClass("com.ss.android.ugc.aweme.main.MainActivity");
            XposedBridge.hookAllMethods(ma, "onResume", new MessageTitleResumeHook());
            startTitlePoll();
            XposedBridge.log(TAG + ": message-title longpress hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": message-title longpress skip "
                    + t.getClass().getSimpleName());
        }
    }

    private static class MessageTitleResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object act = param.thisObject;
                if (!(act instanceof android.app.Activity)) return;
                final android.app.Activity a = (android.app.Activity) act;
                LAST_ACTIVITY = new java.lang.ref.WeakReference<android.app.Activity>(a);
                startTitlePoll();
            } catch (Throwable ignored) {
            }
        }
    }

    private static class MessageTitleAttachTask implements Runnable {
        private final android.app.Activity activity;
        private final int attempt;
        MessageTitleAttachTask(android.app.Activity activity, int attempt) {
            this.activity = activity;
            this.attempt = attempt;
        }
        @Override
        public void run() {
            try {
                if (activity.isDestroyed()) return;
                int id = activity.getResources().getIdentifier("tv_title", "id", TARGET_PKG);
                android.view.View v = id == 0 ? null
                        : activity.getWindow().getDecorView().findViewById(id);
                if (!(v instanceof android.widget.TextView)
                        || !"消息".equals(((android.widget.TextView) v).getText().toString())) {
                    return; // 由 TitlePollTask 每秒重试，直到用户进入消息页
                }
                boolean isNew = false;
                synchronized (GESTURE_TITLES) {
                    if (!GESTURE_TITLES.contains(v)) {
                        GESTURE_TITLES.add(v);
                        isNew = true;
                    }
                }
                if (isNew) {
                    v.setClickable(true);
                    v.setOnClickListener(new MessageTitleNoClick());
                    v.setOnTouchListener(new MessageTitleTouch(activity));
                }
                // 每次挂载轮询都按当前状态同步红点：标题 View 重建后能补画，
                // 停留消息页/聊天页时收到消息也能立即显示。
                syncTitleDot(v);
                messageTitleAttached = true;
                if (isNew) XposedBridge.log(TAG + ": message-title longpress attached");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": message-title attach failed " + t);
            }
        }
    }

    /** 不能在 static 初始化里取 MainLooper：模块 ClassLoader 下它可能是 null。 */
    private static android.os.Handler titleHandler() {
        if (TITLE_HANDLER != null) return TITLE_HANDLER;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Field f = at.getDeclaredField("sMainLooper");
            f.setAccessible(true);
            Object looper = f.get(null);
            if (looper instanceof android.os.Looper) {
                TITLE_HANDLER = new android.os.Handler((android.os.Looper) looper);
                return TITLE_HANDLER;
            }
        } catch (Throwable ignored) {
        }
        TITLE_HANDLER = new android.os.Handler();
        return TITLE_HANDLER;
    }

    private static void startTitlePoll() {
        if (titlePollRunning) return;
        titlePollRunning = true;
        titleHandler().post(new TitlePollTask(0));
    }

    /** 持续轮询「消息」标题：进入消息页挂长按，并实时同步红点（标题 View 重建也能补画）。 */
    private static class TitlePollTask implements Runnable {
        private final int n;
        TitlePollTask(int n) { this.n = n; }
        @Override
        public void run() {
            try {
                android.app.Activity a = LAST_ACTIVITY.get();
                if (a != null && !a.isDestroyed()) {
                    // 每轮都尝试挂载/同步，不再因 messageTitleAttached 提前停止
                    new MessageTitleAttachTask(a, n).run();
                }
            } catch (Throwable ignored) {
            }
            // 持续轮询，不停止：保证停留任意页面时红点状态都能同步到标题
            titleHandler().postDelayed(new TitlePollTask(n + 1), 1000L);
        }
    }

    private static class MessageTitleNoClick implements android.view.View.OnClickListener {
        @Override
        public void onClick(android.view.View v) { }
    }

    private static class MessageTitleTouch implements android.view.View.OnTouchListener {
        private final android.app.Activity activity;
        private long downAt = 0L;
        MessageTitleTouch(android.app.Activity activity) { this.activity = activity; }
        @Override
        public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
            try {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downAt = e.getEventTime();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                        // 点击标题（含长按）视为已查看，清掉红点
                        if (hiddenDotPending) {
                            hiddenDotPending = false;
                            syncTitleDot(v);
                        }
                        if (e.getEventTime() - downAt >= longPressMs) toggleTempPause(activity);
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    // ===== v10.5：收到隐藏联系人消息时，在「消息」标题右侧显示红点 =====
    /** 收到隐藏联系人消息（横幅命中）时调用：置位并在已挂载的标题上画红点。 */
    private static void signalHiddenMessageDot(String reason) {
        if (!notifyMaskActive()) return;
        hiddenDotPending = true;
        XposedBridge.log(TAG + ": hidden-message dot on (" + reason + ")");
        titleHandler().post(new Runnable() {
            @Override
            public void run() {
                synchronized (GESTURE_TITLES) {
                    for (android.view.View v : GESTURE_TITLES) {
                        syncTitleDot(v);
                    }
                }
            }
        });
    }

    /** 每个标题 View 的原始 right drawable，便于幂等还原（弱引用随 View 回收）。 */
    private static final Map<android.view.View, android.graphics.drawable.Drawable> dotOriginals =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<android.view.View, android.graphics.drawable.Drawable>());

    /** 按 hiddenDotPending 状态同步标题红点；幂等，重复调用无副作用。 */
    private static void syncTitleDot(android.view.View v) {
        if (!(v instanceof android.widget.TextView)) return;
        android.widget.TextView tv = (android.widget.TextView) v;
        try {
            android.graphics.drawable.Drawable[] ds = tv.getCompoundDrawables();
            boolean want = hiddenDotPending;
            boolean isDot = ds[2] != null && ds[2] == cachedDot;
            if (want && !isDot) {
                // 首次为该 View 记录原始 drawable（避免把上一次的红点当原值）
                if (!dotOriginals.containsKey(v)) dotOriginals.put(v, ds[2]);
                if (cachedDot == null) {
                    android.graphics.drawable.ShapeDrawable dot =
                            new android.graphics.drawable.ShapeDrawable(
                                    new android.graphics.drawable.shapes.OvalShape());
                    dot.getPaint().setColor(0xFFFF3B30);
                    int size = dpToPx(v.getContext(), 9);
                    dot.setBounds(0, 0, size, size);
                    cachedDot = dot;
                }
                tv.setCompoundDrawables(ds[0], ds[1], cachedDot, ds[3]);
                tv.setCompoundDrawablePadding(dpToPx(v.getContext(), 6));
                XposedBridge.log(TAG + ": title-dot SHOWN");
            } else if (!want && isDot) {
                android.graphics.drawable.Drawable orig = dotOriginals.get(v);
                tv.setCompoundDrawables(ds[0], ds[1], orig, ds[3]);
                XposedBridge.log(TAG + ": title-dot CLEARED");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncTitleDot err " + t);
        }
    }

    private static int dpToPx(android.content.Context c, int dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ============ 个人主页标签逐个隐藏（作品/日常/收藏/喜欢/去过）============
    // 逆向抖X case 1：hook 标签管理器 .(User):List，after 阶段替换返回的 tab type 列表。
    // 返回 List<Integer>，元素为 ProfileTabItem.LIZ()；清单来自 User.profileTabInfoStruct.a。
    // 40.8.0 = X.1010.LJJJJL ；41.3.0 = X.0wC0.LJJJLIIL（方法名也变了，故用数组兼容）。
    private static final String[] PROFILE_TAB_METHODS = {"LJJJLIIL", "LJJJJL"};

    /** 安装个人主页标签隐藏 hook。 */
    private static void installProfileTabHide(ClassLoader cl) {
        String used = DexClassMap.resolve("profile_tab_manager", "X.0wC0");
        try {
            Class<?> c = cl.loadClass(used);
            int hooked = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                boolean nameOk = false;
                for (String nm : PROFILE_TAB_METHODS) {
                    if (nm.equals(m.getName())) { nameOk = true; break; }
                }
                if (!nameOk) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || !ps[0].getName().equals(
                        "com.ss.android.ugc.aweme.profile.model.User")) continue;
                if (!java.util.List.class.isAssignableFrom(m.getReturnType())) continue;
                String key = m.getDeclaringClass().getName() + "." + m.getName();
                if (profileTabHookedClasses.contains(key)) continue;
                profileTabHookedClasses.add(key);
                XposedBridge.hookMethod(m, new ProfileTabListHook());
                XposedBridge.log(TAG + ": profile-tab hook installed " + key);
                hooked++;
            }
            DexClassMap.recordHook("profile_tab_manager", "个人页标签管理",
                    "X.1010", used, hooked > 0, "(User):List x" + hooked);
        } catch (Throwable t) {
            DexClassMap.recordHook("profile_tab_manager", "个人页标签管理",
                    "X.1010", used, false, t.getClass().getSimpleName() + " " + t.getMessage());
            XposedBridge.log(TAG + ": profile-tab hook skip " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    /** hook 标签管理器的 (User):List 返回列表：收集清单 + 按勾选过滤 tab type。 */
    private static class ProfileTabListHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object user = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (user == null) return;
                // 1) 从 User 收集 Tab 清单（type → 显示名），清单收集不受开关影响
                collectProfileTabs(user);
                if (!enabled || !profileTabEnabled || profileTabNames == null
                        || profileTabNames.isEmpty()) return;
                // 2) 只对「自己的主页」生效（避免影响看别人主页）
                if (!isSelfProfile(user)) return;
                Object result = param.getResult();
                if (!(result instanceof java.util.List)) return;
                java.util.List<?> list = (java.util.List<?>) result;
                if (list.isEmpty()) return;
                java.util.List<Object> kept = new java.util.ArrayList<Object>();
                for (Object o : list) {
                    int type = ((Number) o).intValue();
                    String key = "tab:" + type;
                    if (profileTabNames.contains(key)) continue;
                    kept.add(o);
                }
                if (kept.size() == list.size()) return;
                // 至少保留一个标签，否则宿主 TabLayout 可能异常
                if (kept.isEmpty()) kept.add(list.get(0));
                param.setResult(new java.util.ArrayList<Object>(kept));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从 User.profileTabInfoStruct.a(List<ProfileTabItem>) 收集 Tab 清单。 */
    private static void collectProfileTabs(Object user) {
        try {
            Object struct = findFieldValue(user, "profileTabInfoStruct");
            if (struct == null) return;
            Object items = findFieldValue(struct, "a");
            if (!(items instanceof java.util.List)) return;
            for (Object item : (java.util.List<?>) items) {
                if (item == null) continue;
                try {
                    int type = invokeInt(item, "LIZ");
                    String name = invokeString(item, "getName");
                    if (name == null || name.trim().isEmpty()) name = fallbackTabName(type);
                    String key = "tab:" + type;
                    synchronized (discoveredProfileTabs) {
                        if (!discoveredProfileTabs.containsKey(key)) {
                            discoveredProfileTabs.put(key, name.trim());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 判断 User 是否为当前登录用户自己。 */
    private static boolean isSelfProfile(Object user) {
        try {
            String uid = readStringField(user, "uid");
            if (uid == null || uid.isEmpty()) return false;
            ClassLoader cl = user.getClass().getClassLoader();
            Class<?> proxy = Class.forName(
                    "com.ss.android.ugc.aweme.account.AccountProxyService", false, cl);
            java.lang.reflect.Method userService = proxy.getMethod("userService");
            Object svcObj = userService.invoke(null);
            if (svcObj == null) return false;
            java.lang.reflect.Method getCur = svcObj.getClass().getMethod("getCurUserId");
            String cur = (String) getCur.invoke(svcObj);
            return uid.equals(cur);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Tab type → 显示名兜底（抖X硬编码表）。 */
    private static String fallbackTabName(int type) {
        switch (type) {
            case 0: return "作品";
            case 38: return "日常";
            case 16: return "收藏";
            case 46: return "去过";
            case 100: return "喜欢";
            case 101: return "推荐";
            case 45: return "评价";
            default: return "标签 " + type;
        }
    }

    /** 反射读对象里第一个名为 name 的字段值（含父类）。 */
    private static Object findFieldValue(Object o, String name) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Class<?> findClassQuiet(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int invokeInt(Object o, String methodName) throws Exception {
        java.lang.reflect.Method m = findMethod(o.getClass(), methodName);
        if (m == null) throw new NoSuchMethodException(methodName);
        return ((Number) m.invoke(o)).intValue();
    }

    private static String invokeString(Object o, String methodName) throws Exception {
        java.lang.reflect.Method m = findMethod(o.getClass(), methodName);
        if (m == null) throw new NoSuchMethodException(methodName);
        return (String) m.invoke(o);
    }

    /** 在类继承链里按名字找无参方法（含非 public）。 */
    private static java.lang.reflect.Method findMethod(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /** v9.1：自定义删除「我」页顶部入口条（团购带货/我的订单/观看历史/我的钱包/全部功能…） */
    private static void installProfileEntryHide(ClassLoader cl) {
        try {
            Class<?> ma = cl.loadClass("com.ss.android.ugc.aweme.main.MainActivity");
            XposedBridge.hookAllMethods(ma, "onResume", new ProfileEntryScanHook());
            XposedBridge.log(TAG + ": profile-entry hook MainActivity.onResume");
            // 数据层过滤（与 dyhelper-ProfileCardEntry 同思路）：从卡片入口列表里删掉名单项
            installCardEntryListFilter(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": profile-entry skip " + t.getClass().getSimpleName());
        }
    }

    /** 主页 resume 后启动一轮扫描（覆盖首次进入与切回） */
    private static class ProfileEntryScanHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object o = param.thisObject;
                if (!(o instanceof android.app.Activity)) return;
                android.view.View decor =
                        ((android.app.Activity) o).getWindow().getDecorView();
                if (decor == null) return;
                startProfileScan(decor);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 起一个短暂的轮询扫描（切到「我」页时视图是懒加载的，需要多次尝试） */
    private static void startProfileScan(final android.view.View decor) {
        if (profileScanStarted) return;
        profileScanStarted = true;
        Thread t = new Thread(new ProfileScanLoop(decor));
        t.setDaemon(true);
        t.start();
    }

    /** 扫描主循环（具名静态类：规避 D8 对匿名内部类的 NPE）v12：改为常驻，覆盖单Activity多Fragment的Tab切换 */
    private static class ProfileScanLoop implements Runnable {
        private final android.view.View decor;

        ProfileScanLoop(android.view.View decor) {
            this.decor = decor;
        }

        @Override
        public void run() {
            int i = 0;
            try {
                while (true) {
                    Thread.sleep(2000);
                    // 功能关闭则暂停扫描（不退出线程，等用户再开）
                    if (!enabled || !profileEntryEnabled) continue;
                    try {
                        decor.post(new ProfileScanStep(decor, i));
                    } catch (Throwable ignored) {
                        break; // decor 已失效，退出，等下次 onResume 重建
                    }
                    i++;
                }
            } catch (Throwable ignored) {
            } finally {
                profileScanStarted = false;
            }
        }
    }

    /** 数据层过滤：把名单里的卡片入口从数据列表移除（Compose 随之只渲染剩下的） */
    private static void installCardEntryListFilter(ClassLoader cl) {
        try {
            Class<?> svc = cl.loadClass(
                    "com.ss.android.ugc.aweme.profile.panda.PandaCardEntriesServiceV3");
            int n = 0;
            for (java.lang.reflect.Method m : svc.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0) continue;
                try {
                    XposedBridge.hookAllMethods(svc, m.getName(), new CardEntryListFilter());
                    n++;
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": card-entry list filter hooks " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": card-entry filter skip " + t.getClass().getSimpleName());
        }
        // 兜底：Parcelable 反序列化时也会构造
        try {
            Class<?> info = cl.loadClass(
                    "com.ss.android.ugc.aweme.profile.model.CardEntriesInfoV3");
            XposedBridge.hookAllConstructors(info, new CardEntryListFilter());
            XposedBridge.log(TAG + ": card-entry ctor hook ok");
        } catch (Throwable ignored) {
        }
        // v12：KMP 路径（当前抖音主页卡片入口主要走这里）
        installKmpCardEntryHooks(cl);
    }

    /**
     * v12：KMP 逐个隐藏——完全照搬抖X的链路：
     * 1) hook CardEntryListViewModel 的绑定方法(KA)，before 找到承载 cards 的 MutableStateFlow
     *    存进 ThreadLocal；after 清除。
     * 2) hook MutableStateFlow.setValue(Object)，当 thisObject 正是 TLS 里的 flow 且新值是
     *    List<CardCellViewModel> 时，按 cell_card → CardEntry 的标识过滤，替换参数列表。
     * 这样每次列表发射都被拦截，Compose 只会渲染剩下的入口。
     */
    /** 目标 cards flow 的弱引用（不用 TLS：flow 的 emit 常发生在 VM bind 窗口之外） */
    private static volatile java.lang.ref.WeakReference<Object> kmpCardsFlowRef =
            new java.lang.ref.WeakReference<Object>(null);

    private static void installKmpCardEntryHooks(ClassLoader cl) {
        // (1) ViewModel 绑定方法：找 cards flow 存 TLS
        try {
            Class<?> vm = cl.loadClass(
                    "com.ss.kmp.ugc.aweme.profile.cardentry.row_list.CardEntryListViewModel");
            int hooked = 0;
            for (java.lang.reflect.Method m : vm.getDeclaredMethods()) {
                if (m.getName().equals("<init>")) continue;
                Class<?>[] ps = m.getParameterTypes();
                boolean hasMap = false;
                for (Class<?> p : ps) if (java.util.Map.class.isAssignableFrom(p)) { hasMap = true; break; }
                if (!hasMap || m.getReturnType() != void.class) continue;
                try {
                    XposedBridge.hookMethod(m, new KmpViewModelBindHook());
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": kmp vm bind hooks " + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": kmp vm bind skip " + t.getClass().getSimpleName());
        }
        // (2) 每个 cell 绑定时收集入口清单（卡片 id/type/title）
        try {
            Class<?> cell = cl.loadClass(
                    "com.ss.kmp.ugc.aweme.profile.cardentry.view_model.CardCellViewModel");
            int hooked = 0;
            for (java.lang.reflect.Method m : cell.getDeclaredMethods()) {
                if (!m.getName().startsWith("LJIJI")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 2 || !ps[1].getName().contains("CardEntry")) continue;
                try {
                    XposedBridge.hookMethod(m, new KmpCellBindHook());
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": kmp cell bind hooks " + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": kmp cell bind skip " + t.getClass().getSimpleName());
        }
        // (3) hook MutableStateFlow.setValue：拦截 cards 列表发射并过滤
        try {
            Class<?> flowCls = cl.loadClass("kotlinx.coroutines.flow.MutableStateFlow");
            int hooked = 0;
            for (java.lang.reflect.Method m : flowCls.getDeclaredMethods()) {
                if (!m.getName().equals("setValue")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                try {
                    XposedBridge.hookMethod(m, new KmpFlowSetHook());
                    hooked++;
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": kmp flow setValue hooks " + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": kmp flow setValue skip " + t.getClass().getSimpleName());
        }
    }

    /** v12：hook ViewModel 绑定方法，找到承载 CardCellViewModel 列表的 MutableStateFlow 存 TLS */
    private static class KmpViewModelBindHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object vm = param.thisObject;
                if (vm == null) return;
                for (java.lang.reflect.Field f : vm.getClass().getDeclaredFields()) {
                    try {
                        if (!f.getType().getName().contains("MutableStateFlow")) continue;
                        f.setAccessible(true);
                        Object flow = f.get(vm);
                        if (flow == null) continue;
                        Object value = invokeNoArg(flow, "getValue");
                        if (value instanceof java.util.List) {
                            java.util.List<?> l = (java.util.List<?>) value;
                            if (!l.isEmpty() && l.get(0) != null
                                    && l.get(0).getClass().getName().contains("CardCellViewModel")) {
                                kmpCardsFlowRef = new java.lang.ref.WeakReference<Object>(flow);
                                // 动态 hook 该 flow 实现类的 setValue（MutableStateFlow 接口 hook 不到）
                                hookFlowSetValueImpl(flow.getClass());
                                // 立即主动过滤当前值（bind 之后未必再 emit，hook 拦不到）
                                filterFlowNow(flow);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** v12：bind 时立即过滤 flow 当前列表并写回 */
    private static void filterFlowNow(Object flow) {
        try {
            if (!enabled || !profileEntryEnabled || profileEntryNames == null
                    || profileEntryNames.isEmpty()) return;
            Object value = invokeNoArg(flow, "getValue");
            if (!(value instanceof java.util.List)) return;
            java.util.List<?> list = (java.util.List<?>) value;
            if (list.isEmpty()) return;
            Object first = list.get(0);
            if (first == null || !first.getClass().getName().contains("CardCellViewModel")) return;
            java.util.List<Object> kept = new java.util.ArrayList<Object>();
            for (Object cell : list) {
                collectProfileEntry(cell);
                Object card = resolveCardEntry(cell);
                if (card != null && entryMatches(card)) continue;
                kept.add(cell);
            }
            int removed = list.size() - kept.size();
            if (removed > 0) {
                invokeSetValue(flow, kept);
            }
        } catch (Throwable ignored) {
        }
    }

    /** v12：动态 hook flow 实现类的 setValue(Object)（接口/父类 hook 不到） */
    private static final java.util.Set<String> kmpFlowHookedClasses =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static void hookFlowSetValueImpl(Class<?> flowCls) {
        try {
            Class<?> c = flowCls;
            while (c != null && !c.getName().startsWith("kotlin.")) {
                String cname = c.getName();
                if (kmpFlowHookedClasses.contains(cname)) return;
                int hooked = 0;
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    // 实现类里 setValue 被混淆成任意名；只认「单参数且参数可承接 List」的方法
                    if (ps.length != 1) continue;
                    if (ps[0] != Object.class && !java.util.Collection.class.isAssignableFrom(ps[0])
                            && ps[0] != java.util.List.class && !ps[0].isInterface()) continue;
                    try {
                        XposedBridge.hookMethod(m, new KmpFlowSetHook());
                        hooked++;
                    } catch (Throwable ignored) {
                    }
                }
                kmpFlowHookedClasses.add(cname);
                if (hooked > 0) return;
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

    /** v12：hook MutableStateFlow.setValue，拦截 cards 列表发射，按标识逐个过滤 */
    private static class KmpFlowSetHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object targetFlow = kmpCardsFlowRef.get();
                if (targetFlow == null || param.thisObject != targetFlow) return;
                if (param.args == null || param.args.length < 1) return;
                Object newValue = param.args[0];
                if (!(newValue instanceof java.util.List)) return;
                java.util.List<?> list = (java.util.List<?>) newValue;
                if (list.isEmpty()) return;
                Object first = list.get(0);
                if (first == null || !first.getClass().getName().contains("CardCellViewModel")) return;

                // 收集清单
                for (Object cell : list) collectProfileEntry(cell);

                boolean doFilter = enabled && profileEntryEnabled
                        && profileEntryNames != null && !profileEntryNames.isEmpty();
                if (!doFilter) return;

                java.util.List<Object> kept = new java.util.ArrayList<Object>();
                for (Object cell : list) {
                    Object card = resolveCardEntry(cell);
                    if (card != null && entryMatches(card)) continue; // 命中名单 → 丢弃
                    kept.add(cell);
                }
                int removed = list.size() - kept.size();
                if (removed > 0) {
                    param.args[0] = new java.util.ArrayList<Object>(kept);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 反射调用无参方法 */
    private static Object invokeNoArg(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(o);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 反射调用 setValue(Object) */
    private static boolean invokeSetValue(Object flow, Object value) {
        Class<?> c = flow.getClass();
        while (c != null) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setValue")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == Object.class) {
                    try { m.setAccessible(true); m.invoke(flow, value); return true; }
                    catch (Throwable ignored) { }
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** v12：hook CardCellViewModel 绑定，收集单个 CardEntry */
    private static class KmpCellBindHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length < 2) return;
                Object cardEntry = param.args[1];
                if (cardEntry != null) collectProfileEntry(cardEntry);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 过滤参数里的卡片入口列表 */
    private static class CardEntryListFilter extends XC_MethodHook {
        private static long lastLog = 0L;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                // v12：收集清单始终进行（不受开关/名单限制），否则首次无法发现入口
                boolean doFilter = enabled && profileEntryEnabled
                        && profileEntryNames != null && !profileEntryNames.isEmpty();
                int removed = 0;
                for (Object a : param.args) {
                    if (a instanceof java.util.List) {
                        removed += filterEntryList((java.util.List<?>) a, doFilter);
                    } else if (a != null && a.getClass().getName().contains("CardEntries")) {
                        try {
                            java.lang.reflect.Field cf =
                                    a.getClass().getDeclaredField("cardEntries");
                            cf.setAccessible(true);
                            Object v = cf.get(a);
                            if (v instanceof java.util.List) {
                                removed += filterEntryList((java.util.List<?>) v, doFilter);
                            }
                        } catch (Throwable ignored) {
                        }
                    } else if (a != null) {
                        // 兜底：入口对象可能直接作为参数传入
                        collectProfileEntry(a);
                    }
                }
                if (removed > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 800) {
                        lastLog = now;
                        XposedBridge.log(TAG + ": card-entry filtered " + removed
                                + " via " + param.method.getName()
                                + " discovered=" + discoveredProfileEntries.size());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从列表里移除命中名单的入口（按精确标识 id:xxx / type:x，兼容旧按名称匹配） */
    private static int filterEntryList(java.util.List<?> list, boolean doFilter) {
        int removed = 0;
        try {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object e = list.get(i);
                if (e == null) continue;
                // 收集入口清单供面板展示（即使当前不隐藏也要收集）
                collectProfileEntry(e);
                if (!doFilter || !entryMatches(e)) continue;
                try {
                    list.remove(i);
                    removed++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    /**
     * v12：把一个 CardEntry 登记到入口清单。
     * 标识策略与抖X一致：优先 id:cardId（cardId 非空且非占位），否则 type:type。
     * 显示名取 title，空则 subtitle，再空则「入口 <type>」。
     */
    private static void collectProfileEntry(Object e) {
        try {
            e = resolveCardEntry(e);
            if (e == null) return;
            String cn = e.getClass().getName();
            // 只登记真正的卡片入口对象，避免把 List 里的其它模型混进来
            if (cn.indexOf("CardEntry") < 0 && cn.indexOf("cardentry") < 0) return;
            String id = readStringField(e, "cardId", "card_id", "mId", "id");
            int type = readIntField(e, "type", "cardType", "card_type");
            String key = null;
            if (id != null && id.trim().length() > 0 && id.length() <= 64) {
                key = "id:" + id.trim();
            } else if (type > 0) {
                key = "type:" + type;
            }
            if (key == null) return;
            String title = readStringField(e, "title", "cardTitle", "card_title", "name");
            if (title == null || title.trim().isEmpty()) {
                title = readStringField(e, "subtitle", "cardSubtitle");
            }
            if (title == null || title.trim().isEmpty()) {
                title = "入口 " + type;
            }
            synchronized (discoveredProfileEntries) {
                if (!discoveredProfileEntries.containsKey(key)) {
                    discoveredProfileEntries.put(key, title.trim());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 按顺序读第一个存在且非空的 String 字段 */
    private static String readStringField(Object o, String... names) {
        for (String nm : names) {
            try {
                java.lang.reflect.Field f = findFieldQuiet(o.getClass(), nm);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof CharSequence) {
                    String s = v.toString().trim();
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 按顺序读第一个存在且 >0 的 int 字段 */
    private static int readIntField(Object o, String... names) {
        for (String nm : names) {
            try {
                java.lang.reflect.Field f = findFieldQuiet(o.getClass(), nm);
                if (f == null) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof Number) {
                    int x = ((Number) v).intValue();
                    if (x > 0) return x;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 在类及父类中找字段 */
    private static java.lang.reflect.Field findFieldQuiet(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField(name);
            } catch (Throwable ignored) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    /** 若对象是 CardCellViewModel 之类包装，则取出它持有的 CardEntry */
    private static Object resolveCardEntry(Object o) {
        if (o == null) return null;
        try {
            if (o.getClass().getName().contains("CardEntry")
                    && !o.getClass().getName().contains("ViewModel")) return o;
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                try {
                    String fn = f.getName().toLowerCase(java.util.Locale.ROOT);
                    String ft = f.getType().getName();
                    // 字段名含 card 且声明类型是 CardEntry，或是已知 cell_card 字段 a
                    if (ft.contains("profile.model.CardEntry")
                            || (fn.contains("card") && ft.contains("CardEntry"))) {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v != null) return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 元素是否命中名单：精确标识优先，兼容旧按任意 String 字段匹配 */
    private static boolean entryMatches(Object e) {
        try {
            e = resolveCardEntry(e);
            if (e == null) return false;
            String id = readStringField(e, "cardId", "card_id", "mId", "id");
            int type = readIntField(e, "type", "cardType", "card_type");
            String keyById = id == null ? null : "id:" + id.trim();
            String keyByType = type > 0 ? "type:" + type : null;
            if (keyById != null && profileEntryNames.contains(keyById)) return true;
            if (keyByType != null && profileEntryNames.contains(keyByType)) return true;

            for (java.lang.reflect.Field f : e.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(e);
                    if (v instanceof CharSequence) {
                        String sv = v.toString().trim();
                        if (sv.isEmpty() || sv.length() > 32) continue;
                        if (profileEntryNames.contains(sv)) return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 隐藏个人主页卡片入口：名单空=整排兜底；名单非空=按显示名逐个隐藏（Compose/KMP 数据层拿不到列表时的可靠路径） */
    private static void applyProfileEntryHide(android.view.View root, int round) {
        try {
            if (!enabled || !profileEntryEnabled) return;
            if (VIEW_HIDE_OFF) return;
            java.util.List<android.view.View> all = new java.util.ArrayList<>();
            collectAll(root, all, 0);

            boolean perEntry = profileEntryNames != null && !profileEntryNames.isEmpty();
            if (!perEntry) {
                // 旧行为：名单为空 → 整排 GONE
                for (android.view.View v : all) {
                    String cn = v.getClass().getSimpleName();
                    if (!cn.contains("CardEntriesScrollView")
                            && !cn.contains("ProfileCardEntries")) continue;
                    hideEntryView(v, cn, round);
                }
                return;
            }

            // v12 逐个隐藏：算出命中名单的入口显示名
            java.util.Set<String> hideTitles = new java.util.HashSet<String>();
            for (String key : profileEntryNames) {
                String label = null;
                synchronized (discoveredProfileEntries) {
                    label = discoveredProfileEntries.get(key);
                }
                if (label != null && !label.trim().isEmpty()) hideTitles.add(label.trim());
                // 兼容旧配置：直接填的就是中文名
                if (key != null && !key.startsWith("id:") && !key.startsWith("type:")) {
                    hideTitles.add(key.trim());
                }
            }
            if (hideTitles.isEmpty()) return;

            // 找卡片入口条容器，只在其内部按文字隐藏，避免误伤别处同名文本
            android.view.View bar = null;
            for (android.view.View v : all) {
                String cn = v.getClass().getSimpleName();
                if (cn.contains("CardEntriesScrollView") || cn.contains("ProfileCardEntries")) {
                    bar = v; break;
                }
            }
            java.util.List<android.view.View> scope = new java.util.ArrayList<>();
            if (bar != null) collectAll(bar, scope, 0);
            else scope = all;

            for (android.view.View v : scope) {
                if (!(v instanceof android.widget.TextView)) continue;
                String txt = ((android.widget.TextView) v).getText().toString().trim();
                if (!hideTitles.contains(txt)) continue;
                // 找到入口项容器：优先可点击祖先；否则取子View较少的父容器（图标+文字一起藏）
                android.view.View target = clickableAncestor(v, 4);
                if (target == null) {
                    android.view.ViewParent p = v.getParent();
                    if (p instanceof android.view.ViewGroup) {
                        android.view.ViewGroup pg = (android.view.ViewGroup) p;
                        if (pg.getChildCount() <= 4 && pg != bar) target = pg;
                    }
                }
                if (target == null) target = v;
                hideEntryView(target, "entry:" + txt, round);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把一个入口 View 设为 GONE 并记录原 visibility */
    private static void hideEntryView(android.view.View v, String tag, int round) {
        try {
            if (v == null || v.getVisibility() == android.view.View.GONE) return;
            synchronized (PROFILE_ENTRY_HIDDEN) {
                if (!PROFILE_ENTRY_HIDDEN.containsKey(v)) {
                    PROFILE_ENTRY_HIDDEN.put(v, Integer.valueOf(v.getVisibility()));
                }
            }
            v.setVisibility(android.view.View.GONE);
        } catch (Throwable ignored) {
        }
    }

    /** 单次扫描步骤 */
    private static class ProfileScanStep implements Runnable {
        private final android.view.View decor;
        private final int round;

        ProfileScanStep(android.view.View decor, int round) {
            this.decor = decor;
            this.round = round;
        }

        @Override
        public void run() {
            applyProfileEntryHide(decor, round);
        }
    }

    /** 收集所有 View（含非文本），限制数量 */
    private static void collectAll(android.view.View v,
                                   java.util.List<android.view.View> out, int depth) {
        if (v == null || depth > 32 || out.size() > 1500) return;
        if (v.getVisibility() != android.view.View.VISIBLE) return;
        out.add(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = Math.min(g.getChildCount(), 40);
            for (int i = 0; i < n; i++) {
                collectAll(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** 从文本向上找可点击的入口项容器 */
    private static android.view.View clickableAncestor(android.view.View v, int maxUp) {
        android.view.View cur = v;
        for (int i = 0; i < maxUp && cur != null; i++) {
            android.view.ViewParent p;
            try {
                p = cur.getParent();
            } catch (Throwable t) {
                return null;
            }
            if (!(p instanceof android.view.View)) return null;
            android.view.View pv = (android.view.View) p;
            if (pv.isClickable()) return pv;
            cur = pv;
        }
        return null;
    }

    /** 关闭开关时恢复被隐藏的入口项 */
    static void restoreProfileEntries() {
        synchronized (PROFILE_ENTRY_HIDDEN) {
            for (java.util.Map.Entry<android.view.View, Integer> e
                    : new java.util.ArrayList<>(PROFILE_ENTRY_HIDDEN.entrySet())) {
                try {
                    android.view.View v = e.getKey();
                    if (v != null && e.getValue() != null) v.setVisibility(e.getValue());
                } catch (Throwable ignored) {
                }
            }
            PROFILE_ENTRY_HIDDEN.clear();
        }
    }

    /**
     * v8.9：聊天记录空间管理页（我→清理缓存→聊天记录）按昵称隐藏行。
     * 数据结构：CleanStorageAdapter extends BaseAdapter，数据在 BaseAdapter.x（List），
     * 元素类型 0PXn 只含数值 id 与大小统计（LJI/LJII/LJIIIIZZ），**没有 uid / 昵称**，
     * 所以无法在数据层过滤；改为在行绑定时按昵称判断，命中即把 itemView 高度压 0 并 GONE
     * （RecyclerView 中测量高度 0 的行不占位，视觉上等同于删除）。
     * 跟随「隐藏消息列表会话」开关。
     */
    private static void installCleanStorageFilter(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass(
                    "com.ss.android.ugc.aweme.im.business.cleanstoragepage.view.CleanStorageAdapter");
            XposedBridge.hookAllMethods(c, "onBindBasicViewHolder", new CleanRowFilter());
            // RecyclerView 布局后会重置行尺寸，需在布局完成后再纠正一次
            Class<?> rvCls = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView", cl);
            XposedBridge.hookAllMethods(rvCls, "onLayout", new CleanRvLayoutHook());
            XposedBridge.hookAllMethods(rvCls, "onMeasure", new CleanRvMeasureHook());
            XposedBridge.hookAllMethods(rvCls, "onMeasure", new CleanRvMeasureHook());
            XposedBridge.log(TAG + ": clean-storage filter hook ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": clean-storage skip " + t.getClass().getSimpleName());
        }
    }

    /** 绑定行后按昵称决定是否压掉该行 */
    private static class CleanRowFilter extends XC_MethodHook {
        private long lastLog = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object holder = param.args[0];
                if (holder == null) return;
                Object itemView = XposedHelpers.getObjectField(holder, "itemView");
                if (!(itemView instanceof android.view.View)) return;
                android.view.View row = (android.view.View) itemView;

                boolean hide = false;
                String title = null;
                if (enabled && cleanStorageHidden) {
                    title = rowTitle(row);
                    if (title != null && isHiddenName(title)) hide = true;
                }
                if (hide) {
                    // 数据层真正删除：RecyclerView 会覆盖 View 层尺寸修改，只能改数据
                    try {
                        Object posObj = param.args.length > 1 ? param.args[1] : null;
                        if (posObj instanceof Integer) {
                            final int pos = (Integer) posObj;
                            final Object ad = param.thisObject;
                            if (PENDING_DEL.add(pos)) {
                                row.post(new RemoveRowTask(ad, pos));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                android.view.ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (hide) {
                    if (lp != null && lp.height != 0) {
                        row.setTag(0x7E11, lp.height);
                        lp.height = 0;
                        row.setLayoutParams(lp);
                    }
                    row.setVisibility(android.view.View.GONE);
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 800) {
                        lastLog = now;
                        XposedBridge.log(TAG + ": clean-row hidden " + title);
                    }
                } else {
                    // 复用行可能被上次压过，恢复原样
                    if (lp != null && lp.height == 0) {
                        Object o = row.getTag(0x7E11);
                        lp.height = o instanceof Integer
                                ? (Integer) o : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                        row.setLayoutParams(lp);
                    }
                    if (row.getVisibility() != android.view.View.VISIBLE) {
                        row.setVisibility(android.view.View.VISIBLE);
                    }
                    row.requestLayout();
                    row.requestLayout();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 清理页待从数据源移除的 position（降序删除，避免索引偏移） */
    private static final java.util.Set<Integer> PENDING_DEL =
            java.util.Collections.synchronizedSet(new java.util.TreeSet<Integer>());

    /** 从 adapter 数据源移除一行，然后刷新 */
    private static class RemoveRowTask implements Runnable {
        private final Object adapter;
        private final int pos;

        RemoveRowTask(Object adapter, int pos) {
            this.adapter = adapter;
            this.pos = pos;
        }

        @Override
        public void run() {
            try {
                java.util.List<Integer> del = new java.util.ArrayList<>();
                synchronized (PENDING_DEL) {
                    del.addAll(PENDING_DEL);
                    PENDING_DEL.clear();
                }
                if (del.isEmpty()) return;
                Object raw = XposedHelpers.getObjectField(adapter, "x");
                if (!(raw instanceof java.util.List)) return;
                java.util.List<?> list = (java.util.List<?>) raw;
                del.add(pos);
                java.util.Collections.sort(del, java.util.Collections.reverseOrder());
                int removed = 0;
                int last = -1;
                for (int p : del) {
                    if (p == last) continue;
                    last = p;
                    if (p >= 0 && p < list.size()) {
                        try {
                            list.remove(p);
                            removed++;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (removed > 0) {
                    XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                    XposedBridge.log(TAG + ": clean-data removed " + removed
                            + " left=" + list.size());
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": clean-data failed " + t.getClass().getSimpleName());
            }
        }
    }

    /** 测量前把隐藏行设为 GONE（RecyclerView 会跳过 GONE 子项，真正塌陷） */
    private static class CleanRvMeasureHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            applyCleanCollapse(param.thisObject, true);
        }
    }

    /** 清理页 RecyclerView 布局完成后再压一次隐藏行 */
    private static class CleanRvLayoutHook extends XC_MethodHook {
        private long lastLog = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !cleanStorageHidden) return;
                if (names == null || names.isEmpty()) return;
                Object rv = param.thisObject;
                if (rv == null) return;
                Object ad = XposedHelpers.callMethod(rv, "getAdapter");
                if (ad == null) return;
                String an = ad.getClass().getName();
                String anl = an.toLowerCase(java.util.Locale.ROOT);
                if (!anl.contains("clean")) return;

                int n = (Integer) XposedHelpers.callMethod(rv, "getChildCount");
                int hidden = 0;
                for (int i = 0; i < n; i++) {
                    Object o = XposedHelpers.callMethod(rv, "getChildAt", i);
                    if (!(o instanceof android.view.View)) continue;
                    android.view.View cv = (android.view.View) o;
                    android.view.ViewGroup.LayoutParams lp = cv.getLayoutParams();
                    if (lp == null) continue;
                    String t = rowTitle(cv);
                    if (t != null && isHiddenName(t)) {
                        if (lp.height != 0) {
                            lp.height = 0;
                            cv.setLayoutParams(lp);
                        }
                        if (cv.getVisibility() != android.view.View.INVISIBLE) {
                            cv.setVisibility(android.view.View.INVISIBLE);
                        }
                        hidden++;
                    }
                }
                if (hidden > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastLog > 3000) {
                        lastLog = now;
                        XposedBridge.log(TAG + ": clean-rv collapsed " + hidden);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 折叠清理页里命中名单的行（gone=true 用 GONE，更彻底；false 用 INVISIBLE+高0） */
    private static void applyCleanCollapse(Object rv, boolean gone) {
        try {
            if (!enabled || !cleanStorageHidden) return;
            if (names == null || names.isEmpty()) return;
            if (rv == null) return;
            Object ad = XposedHelpers.callMethod(rv, "getAdapter");
            if (ad == null) return;
            String an = ad.getClass().getName();
            if (!an.toLowerCase(java.util.Locale.ROOT).contains("clean")) return;
            int n = (Integer) XposedHelpers.callMethod(rv, "getChildCount");
            for (int i = 0; i < n; i++) {
                Object o = XposedHelpers.callMethod(rv, "getChildAt", i);
                if (!(o instanceof android.view.View)) continue;
                android.view.View cv = (android.view.View) o;
                String t = rowTitle(cv);
                if (t == null || !isHiddenName(t)) continue;
                android.view.ViewGroup.LayoutParams lp = cv.getLayoutParams();
                if (lp != null && lp.height != 0) {
                    lp.height = 0;
                    cv.setLayoutParams(lp);
                }
                if (cv.getVisibility() != android.view.View.GONE) {
                    cv.setVisibility(android.view.View.GONE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 取行内第一个「非大小文本」的 TextView 内容（会话/群昵称） */
    private static String rowTitle(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null) {
                String t = cs.toString().trim();
                if (t.length() > 0 && !t.matches("^[0-9.]+\\s*(B|KB|MB|GB)$")) return t;
            }
            return null;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = Math.min(g.getChildCount(), 12);
            for (int i = 0; i < n; i++) {
                String r = rowTitle(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 昵称是否命中隐藏名单（names 集合；空字符串忽略） */
    private static boolean isHiddenName(String title) {
        if (title == null) return false;
        Set<String> ns = names;
        if (ns == null || ns.isEmpty()) return false;
        String t = title.trim();
        if (t.isEmpty()) return false;
        if (ns.contains(t)) return true;
        for (String n : ns) {
            if (n == null) continue;
            String nn = n.trim();
            if (nn.length() >= 2 && t.equals(nn)) return true;
        }
        return false;
    }

    /**
     * v8.4：入口级隐藏。    /**
     * v8.4：入口级隐藏。
     * 1) 「我」页右上角功能菜单的「观看历史」行 —— 开关 A 控制，整行 GONE。
     * 2) 左滑侧边栏的入口 —— 开关 B（自定义删除）控制，按名称名单在 GridLayout 中移除并补位；
     *    开启后也可直接点击侧边栏某一项，把它加入删除名单。
     */
    private static void installSidebarEntryHide(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(android.widget.TextView.class, "setText",
                    new EntryTextHook());
            XposedBridge.log(TAG + ": sidebar-entry hook TextView.setText");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sidebar-entry skip " + t.getClass().getSimpleName());
        }
        // 每次布局后纠正：抖音再次打开侧边栏会按原始行列重排，需重新移除并补位
        try {
            XposedBridge.hookAllMethods(android.widget.GridLayout.class, "onLayout",
                    new GridLayoutFixHook());
            XposedBridge.log(TAG + ": sidebar-entry hook GridLayout.onLayout");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": grid-fix skip " + t.getClass().getSimpleName());
        }
    }

    /** 文本设置时判断是否需要处理（先快速过滤，避免给所有 setText 都排任务） */
    private static class EntryTextHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!enabled) return;
                Object a0 = param.args[0];
                if (!(a0 instanceof CharSequence)) return;
                String text = a0.toString().trim();
                if (text.isEmpty() || text.length() > 24) return;
                if (!(param.thisObject instanceof android.view.View)) return;
                android.view.View tv = (android.view.View) param.thisObject;

                boolean maybe = "观看历史".equals(text)
                        || (sidebarHideEnabled && sidebarHideNames.contains(text))
                        || (sidebarHideRightEnabled && sidebarHideRightNames.contains(text));
                if (!maybe) return;
                tv.postDelayed(new HideEntryTask(tv, text), 80);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 判定 View 位于哪一种侧边栏：1=左滑侧边栏（右侧面板，含离线缓存等网格），
     * 2=「我」页右滑侧边栏（纵向菜单，含观看历史等行），0=都不是。
     * 注意 "profilesidebar.panel" 也包含 "sidebar.panel"，所以必须先判右滑。
     */
    private static int sidebarKind(android.view.View v) {
        // 先确认确实在侧边栏体系内
        android.view.View side = null;
        android.view.View cur = v;
        boolean inSidebar = false;
        for (int i = 0; i < 12 && cur != null; i++) {
            String cn = cur.getClass().getName();
            if (cn.contains("SideBar") || cn.contains("sidebar")) {
                inSidebar = true;
            }
            if (cn.contains("SideBarNestedScrollView") || cn.contains("SideBarPanel")) {
                side = cur;
                break;
            }
            try {
                android.view.ViewParent p = cur.getParent();
                if (!(p instanceof android.view.View)) break;
                cur = (android.view.View) p;
            } catch (Throwable t) {
                break;
            }
        }
        if (!inSidebar) return 0;
        // 两个面板共用 sidebar.panel 模板，用「面板里是否有网格」区分：
        // 有网格 = 左滑侧边栏（右侧抽屉，含离线缓存等入口），无网格 = 右滑侧边栏（纵向列表）
        android.view.View scope = side != null ? side : v.getRootView();
        return containsGrid(scope, 0) ? 1 : 2;
    }

    /** 子树中是否存在「入口网格」（子项 >= 6 的 GridLayout） */
    private static boolean containsGrid(android.view.View v, int depth) {
        if (v == null || depth > 12) return false;
        if (v instanceof android.widget.GridLayout) {
            if (((android.widget.GridLayout) v).getChildCount() >= 6) return true;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = Math.min(g.getChildCount(), 40);
            for (int i = 0; i < n; i++) {
                if (containsGrid(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    /** 取 View 子树中第一个非空文本（入口名称） */
    private static String entryNameOf(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null && cs.toString().trim().length() > 0) return cs.toString().trim();
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && i < 12; i++) {
                String r = entryNameOf(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 统计第一行的子项数（列数）——必须在移除前调用 */
    private static int detectColumns(android.widget.GridLayout gl) {
        try {
            int n = gl.getChildCount();
            int firstTop = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                android.view.View c = gl.getChildAt(i);
                if (c.getHeight() > 0) firstTop = Math.min(firstTop, c.getTop());
            }
            int cols = 0;
            if (firstTop != Integer.MAX_VALUE) {
                for (int i = 0; i < n; i++) {
                    if (gl.getChildAt(i).getTop() == firstTop) cols++;
                }
            }
            if (cols <= 0 || cols > 5) {
                int gc = gl.getColumnCount();
                cols = (gc > 0 && gc <= 5) ? gc : 3;
            }
            return cols;
        } catch (Throwable t) {
            return 3;
        }
    }

    /** 网格补位：按行优先重建子项 LayoutParams，使移除后的项依次前移 */
    private static void relayoutGrid(android.widget.GridLayout gl, int cols) {
        try {
            if (cols <= 0) cols = 3;
            int n = gl.getChildCount();
            for (int i = 0; i < n; i++) {
                android.view.View c = gl.getChildAt(i);
                android.view.ViewGroup.LayoutParams old = c.getLayoutParams();
                int w = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                int h = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                int ml = 0, mt = 0, mr = 0, mb = 0;
                if (old instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mp = (android.view.ViewGroup.MarginLayoutParams) old;
                    w = mp.width;
                    h = mp.height;
                    ml = mp.leftMargin;
                    mt = mp.topMargin;
                    mr = mp.rightMargin;
                    mb = mp.bottomMargin;
                }
                android.widget.GridLayout.LayoutParams nlp =
                        new android.widget.GridLayout.LayoutParams(
                                android.widget.GridLayout.spec(i / cols, 1),
                                android.widget.GridLayout.spec(i % cols, 1));
                nlp.width = w;
                nlp.height = h;
                nlp.setMargins(ml, mt, mr, mb);
                c.setLayoutParams(nlp);
            }
            gl.requestLayout();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": relayout failed " + t);
        }
    }

    /** 延迟二次纠正：抖音可能在布局后又按模板重设位置 */
    private static class RelayoutTask implements Runnable {
        private final android.widget.GridLayout gl;
        private final int cols;

        RelayoutTask(android.widget.GridLayout gl, int cols) {
            this.gl = gl;
            this.cols = cols;
        }

        @Override
        public void run() {
            try {
                if (!enabled || !sidebarHideEnabled) return;
                if (gl.getChildCount() <= 0) return;
                relayoutGrid(gl, cols);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按名称隐藏入口：侧边栏走网格移除，菜单行走 GONE */
    private static class HideEntryTask implements Runnable {
        private final android.view.View tv;
        private final String name;
        private static long lastLog = 0L;

        HideEntryTask(android.view.View tv, String name) {
            this.tv = tv;
            this.name = name;
        }

        @Override
        public void run() {
            try {
                if (!enabled) return;
                int kind = sidebarKind(tv);
                try {
                    if ("观看历史".equals(name) && PROBE2.add(name + kind)) {
                        StringBuilder pc = new StringBuilder();
                        android.view.View wv = tv;
                        for (int i = 0; i < 12 && wv != null; i++) {
                            pc.append(wv.getClass().getName());
                            try {
                                android.view.ViewParent pp = wv.getParent();
                                if (!(pp instanceof android.view.View)) break;
                                wv = (android.view.View) pp;
                                pc.append(" < ");
                            } catch (Throwable t) {
                                break;
                            }
                        }
                        int[] loc = new int[2];
                        tv.getLocationOnScreen(loc);
                        XposedBridge.log(TAG + ": entry-probe2 name=" + name
                                + " kind=" + kind + " loc=" + loc[0] + "," + loc[1]
                                + " chain=" + pc);
                    }
                } catch (Throwable ignored) {
                }
                if (kind == 1) {
                    // 左滑侧边栏：只用左滑名单
                    if (!sidebarHideEnabled) return;
                    if (sidebarHideNames == null || !sidebarHideNames.contains(name)) return;
                } else if (kind == 2) {
                    // 右滑侧边栏：只用右滑名单
                    if (!sidebarHideRightEnabled) return;
                    if (sidebarHideRightNames == null
                            || !sidebarHideRightNames.contains(name)) return;
                } else {
                    return;
                }
                float d = tv.getResources().getDisplayMetrics().density;
                android.view.View target = null;
                android.view.View cur = tv;
                for (int i = 0; i < 10 && cur != null; i++) {
                    android.view.View parent;
                    try {
                        parent = (android.view.View) cur.getParent();
                    } catch (Throwable t) {
                        break;
                    }
                    if (parent == null) break;
                    if (parent.isClickable() || parent.getHeight() > (int) (56 * d)) {
                        target = parent;
                        break;
                    }
                    cur = parent;
                }
                if (target == null) return;

                String result;
                if (kind == 1) {
                    result = removeFromGrid(target);
                } else {
                    result = "gone";
                    if (target.getVisibility() != android.view.View.GONE) {
                        synchronized (SIDEBAR_HIDDEN) {
                            SIDEBAR_HIDDEN.put(target, new int[]{target.getVisibility()});
                        }
                        target.setVisibility(android.view.View.GONE);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastLog > 800) {
                    lastLog = now;
                    XposedBridge.log(TAG + ": sidebar-entry hidden " + name
                            + " (" + (kind == 1 ? "left" : "right") + ") " + result);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 布局完成后纠正侧边栏网格：把名单里的入口重新移除并补位 */
    private static class GridLayoutFixHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !sidebarHideEnabled) return;
                if (sidebarHideNames == null || sidebarHideNames.isEmpty()) return;
                Object o = param.thisObject;
                if (!(o instanceof android.widget.GridLayout)) return;
                android.widget.GridLayout gl = (android.widget.GridLayout) o;
                boolean known;
                synchronized (SIDEBAR_GRIDS) {
                    known = SIDEBAR_GRIDS.contains(gl);
                }
                if (!known) {
                    if (!isLeftSidebarGrid(gl)) return;
                    synchronized (SIDEBAR_GRIDS) {
                        SIDEBAR_GRIDS.add(gl);
                    }
                }
                applyGridHides(gl);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 网格是否位于左滑侧边栏内（且不是「我」页右滑侧边栏） */
    private static boolean isLeftSidebarGrid(android.view.View v) {
        StringBuilder sb = new StringBuilder();
        android.view.View cur = v;
        for (int i = 0; i < 10 && cur != null; i++) {
            sb.append(cur.getClass().getName()).append('|');
            try {
                android.view.ViewParent p = cur.getParent();
                if (!(p instanceof android.view.View)) break;
                cur = (android.view.View) p;
            } catch (Throwable t) {
                break;
            }
        }
        String c = sb.toString().toLowerCase(java.util.Locale.ROOT);
        if (c.contains("profilesidebar")) return false;
        return c.contains("sidebar.panel") || c.contains("sidebarnestedscrollview");
    }

    /** 从网格中移除名单里的项，并按原列数重排 */
    private static void applyGridHides(android.widget.GridLayout gl) {
        try {
            if (sidebarHideNames == null || sidebarHideNames.isEmpty()) return;
            java.util.List<android.view.View> del = new java.util.ArrayList<>();
            int n = gl.getChildCount();
            for (int i = 0; i < n; i++) {
                android.view.View c = gl.getChildAt(i);
                String nm = entryNameOf(c);
                if (nm == null) continue;
                nm = nm.trim();
                if (sidebarHideNames.contains(nm)) del.add(c);
            }
            if (del.isEmpty()) return;
            int cols = detectColumns(gl);
            for (android.view.View v : del) {
                try {
                    gl.removeView(v);
                } catch (Throwable ignored) {
                }
            }
            relayoutGrid(gl, cols);
            try {
                gl.postDelayed(new RelayoutTask(gl, cols), 120);
                gl.postDelayed(new RelayoutTask(gl, cols), 400);
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": grid-fix removed " + del.size());
        } catch (Throwable ignored) {
        }
    }

    /** 从网格容器移除一项并重排补位；非网格容器退化为 GONE */
    private static String removeFromGrid(android.view.View target) {
        try {
            android.view.ViewParent vp = target.getParent();
            if (!(vp instanceof android.view.ViewGroup)) {
                target.setVisibility(android.view.View.GONE);
                return "gone(no-parent)";
            }
            android.view.ViewGroup g = (android.view.ViewGroup) vp;
            String gn = g.getClass().getName();
            if (gn.contains("RecyclerView") || gn.contains("ListView")) {
                target.setVisibility(android.view.View.GONE);
                return "gone(recycler)";
            }
            int idx = g.indexOfChild(target);
            // 列数必须在移除前统计，否则第一行项数变化会算错
            int cols = (g instanceof android.widget.GridLayout)
                    ? detectColumns((android.widget.GridLayout) g) : 0;
            synchronized (SIDEBAR_REMOVED) {
                SIDEBAR_REMOVED.put(target, new Object[]{g, idx, cols});
            }
            g.removeView(target);
            String r = "removed idx=" + idx + " from " + g.getClass().getSimpleName();
            if (g instanceof android.widget.GridLayout) {
                relayoutGrid((android.widget.GridLayout) g, cols);
                r += " cols=" + cols + " + relayout";
            }
            return r;
        } catch (Throwable t) {
            try {
                target.setVisibility(android.view.View.GONE);
            } catch (Throwable ignored) {
            }
            return "fallback-gone";
        }
    }

    /**
     * v8.2 诊断：抓主页访问上报请求。hook okhttp newCall + TTNet HttpRequestInfo，
     * 打印 URL（访问记录应是独立 API，不是埋点）。
     */
    private static void installUrlSpy(ClassLoader cl) {
        int ok = 0;
        try {
            Class<?> oc = cl.loadClass("okhttp3.OkHttpClient");
            XposedBridge.hookAllMethods(oc, "newCall", new OkhttpUrlSpy());
            XposedBridge.log(TAG + ": url-spy okhttp newCall");
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": url-spy okhttp skip");
        }
        try {
            Class<?> hri = cl.loadClass("com.bytedance.ttnet.http.HttpRequestInfo");
            for (java.lang.reflect.Method m : hri.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": ttnet-m HttpRequestInfo#" + m.getName() + "(" + sb + ")");
            }
            for (java.lang.reflect.Field fd : hri.getDeclaredFields()) {
                XposedBridge.log(TAG + ": ttnet-f HttpRequestInfo." + fd.getName()
                        + " : " + fd.getType().getSimpleName());
            }
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": url-spy ttnet skip");
        }
        // 字节 retrofit2 的请求对象（Java 层，TTNet 之前的必经点）
        try {
            Class<?> rc = cl.loadClass("com.bytedance.retrofit2.client.Request");
            for (java.lang.reflect.Method m : rc.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": rt-m Request#" + m.getName() + "(" + sb + ")");
            }
            for (java.lang.reflect.Field fd : rc.getDeclaredFields()) {
                XposedBridge.log(TAG + ": rt-f Request." + fd.getName()
                        + " : " + fd.getType().getSimpleName());
            }
            XposedBridge.hookAllConstructors(rc, new RetrofitReqHook());
            XposedBridge.log(TAG + ": rt-hook Request ctor ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": rt-hook skip " + t.getClass().getSimpleName());
        }
        // 字节 retrofit2 的请求对象（Java 层，TTNet 之前的必经点）
        try {
            Class<?> rc = cl.loadClass("com.bytedance.retrofit2.client.Request");
            for (java.lang.reflect.Method m : rc.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": rt-m Request#" + m.getName() + "(" + sb + ")");
            }
            for (java.lang.reflect.Field fd : rc.getDeclaredFields()) {
                XposedBridge.log(TAG + ": rt-f Request." + fd.getName()
                        + " : " + fd.getType().getSimpleName());
            }
            XposedBridge.hookAllConstructors(rc, new RetrofitReqHook());
            XposedBridge.log(TAG + ": rt-hook Request ctor ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": rt-hook skip " + t.getClass().getSimpleName());
        }
        XposedBridge.log(TAG + ": url-spy installed " + ok);
    }

    /** retrofit Request 构造：打印访问/主页相关 URL */
    private static class RetrofitReqHook extends XC_MethodHook {
        private long last = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object self = param.thisObject;
                if (self == null) return;
                String url = "";
                for (String fn : new String[]{"mUrl", "url", "mUri"}) {
                    Object v = getFieldDeep(self, fn);
                    if (v != null) {
                        url = String.valueOf(v);
                        break;
                    }
                }
                if (url.isEmpty()) {
                    try {
                        url = String.valueOf(XposedHelpers.callMethod(self, "url"));
                    } catch (Throwable ignored) {
                    }
                }
                if (url.isEmpty()) return;
                // 关键：只打印含隐藏联系人 uid 的请求（访问上报必然带目标 uid）
                boolean hit = false;
                for (String u : uids) {
                    if (u.length() > 6 && url.contains(u)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) return;
                long now = System.currentTimeMillis();
                if (now - last < 300) return;
                last = now;
                XposedBridge.log(TAG + ": rt-hit " + (url.length() > 500 ? url.substring(0, 500) : url));
            } catch (Throwable ignored) {
            }
        }
    }

    /** okhttp newCall：打印访问相关 URL */    /** okhttp newCall：打印访问相关 URL */    /** okhttp newCall：打印访问相关 URL */
    private static class OkhttpUrlSpy extends XC_MethodHook {
        private long last = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                for (Object a : param.args) {
                    if (a == null) continue;
                    String url = "";
                    try {
                        url = String.valueOf(XposedHelpers.callMethod(a, "url"));
                    } catch (Throwable t) {
                        continue;
                    }
                    String low = url.toLowerCase();
                    if (!low.contains("visit") && !low.contains("profile")) continue;
                    long now = System.currentTimeMillis();
                    if (now - last < 500) return;
                    last = now;
                    XposedBridge.log(TAG + ": url-spy " + shortUrl(url));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v8.1 诊断：定位「主页访问记录」上报点。
     * dyhelper 的实现思路：ProfileVisitRecordBlockHook 拦截「主页访问埋点」4/4 重载，
     * 从源头让服务端不记录访问（而不是隐藏列表）——本方法先 dump 事件名确认。
     */
    private static void installVisitReportSpy(ClassLoader cl) {
        String[] cls = {"com.ss.android.common.applog.AppLog", "com.bytedance.applog.AppLog"};
        int ok = 0;
        for (String cn : cls) {
            try {
                Class<?> c = cl.loadClass(cn);
                XposedBridge.hookAllMethods(c, "onEvent", new VisitBlockHook());
                XposedBridge.hookAllMethods(c, "onEventV3", new VisitBlockHook());
                XposedBridge.log(TAG + ": applog spy on " + cn);
                ok++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": applog spy skip " + cn);
            }
        }
        XposedBridge.log(TAG + ": applog spy installed " + ok);
        // dump Lynx 网络模块签名（HttpResponse/HttpRequest/CallbackImpl）
        String[] netCls = {"com.lynx.jsbridge.network.HttpResponse",
                "com.lynx.jsbridge.network.HttpRequest",
                "com.lynx.jsbridge.CallbackImpl"};
        for (String cn : netCls) {
            try {
                Class<?> c = cl.loadClass(cn);
                java.lang.reflect.Field[] flds = c.getDeclaredFields();
                for (java.lang.reflect.Field f : flds) {
                    XposedBridge.log(TAG + ": lynxnet-field " + c.getSimpleName()
                            + "." + f.getName() + " : " + f.getType().getSimpleName());
                }
                java.lang.reflect.Method[] ms = c.getDeclaredMethods();
                for (java.lang.reflect.Method m : ms) {
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                    XposedBridge.log(TAG + ": lynxnet " + c.getSimpleName()
                            + "#" + m.getName() + "(" + sb + ")");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": lynxnet skip " + cn);
            }
        }
    }

    /** 拦截隐藏联系人的「进入主页」埋点上报：服务端不记录访问 → 不出现在经常访问/访问记录 */
    private static class VisitBlockHook extends XC_MethodHook {
        private long lastLog = 0L;

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                boolean targetEvent = false;
                org.json.JSONObject js = null;
                for (Object a : param.args) {
                    if (a instanceof String) {
                        String sv = (String) a;
                        String low = sv.toLowerCase();
                        if ("enter_personal_tab".equals(sv) || low.contains("personal_tab")
                                || low.contains("profile_visit") || low.contains("visit_record")
                                || low.contains("user_visit") || low.contains("visit_user")) {
                            targetEvent = true;
                        }
                    } else if (a instanceof org.json.JSONObject) {
                        js = (org.json.JSONObject) a;
                    }
                }
                if (!targetEvent || js == null) return;
                if (!jsonContainsHiddenUid(js)) return;
                param.setResult(null);
                long now = System.currentTimeMillis();
                if (now - lastLog > 1500) {
                    lastLog = now;
                    XposedBridge.log(TAG + ": visit-block enter_personal_tab (hidden uid)");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 埋点事件监视：dump 含 visit/profile/homepage 的事件名 */
    private static class VisitEventSpy extends XC_MethodHook {
        private long last = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                StringBuilder sb = new StringBuilder(param.method.getName());
                boolean relevant = false;
                for (Object a : param.args) {
                    if (a == null) continue;
                    if (a instanceof String) {
                        String sv = (String) a;
                        sb.append(" s[").append(sv).append(']');
                        String low = sv.toLowerCase();
                        if (low.contains("visit") || low.contains("profile")
                                || low.contains("homepage")) relevant = true;
                    } else if (a instanceof org.json.JSONObject) {
                        String js = a.toString();
                        sb.append(" j[").append(js.length() > 350 ? js.substring(0, 350) : js).append(']');
                        if (js.contains("visit") || js.contains("profile")) relevant = true;
                        for (String u : uids) {
                            if (u.length() > 6 && js.contains(u)) {
                                relevant = true;
                                sb.append(" <<HIDDEN_UID>>");
                                break;
                            }
                        }
                    }
                }
                if (!relevant) return;
                long now = System.currentTimeMillis();
                if (now - last < 300) return;
                last = now;
                XposedBridge.log(TAG + ": evt " + sb);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * v8.1 新功能：观看历史 →「用户」列表（以及「我经常访问的人」「我的访问记录」）
     * 按 uid 隐藏。这些页面是 AnnieX/Lynx 跨端渲染（channel=watch_history），
     * 数据在前端 JS 侧，Java UI 层拿不到 uid，因此从网络响应层拦截：
     * hook okhttp RealResponseBody.string()，对含用户列表特征（nickname+uid）的 JSON，
     * 把「自身或 user/author 子对象的 uid 命中隐藏名单」的数组元素整项移除。
     * 由「隐藏主页访客记录」(visitorHidden) 开关控制（同属访问记录类隐私列表）。
     */
    private static long lastLynxDiag = 0L;

    private static void installLynxHistoryFilter(ClassLoader cl) {
        // 1) 类探测：确认当前抖音版本里可用的 hook 点（诊断）
        String[] probe = {
                "okhttp3.ResponseBody",
                "okhttp3.internal.http.RealResponseBody",
                "okhttp3.OkHttpClient",
                "com.lynx.tasm.LynxView",
                "com.lynx.tasm.LynxTemplateRender",
                "com.lynx.tasm.network.LynxFetchModule",
                "com.lynx.tasm.behavior.ui.list.UIListAdapter",
                "com.bytedance.android.anniex.ui.AnnieXLynxView",
                "com.ss.android.ugc.aweme.net.NetworkService",
                "com.bytedance.retrofit2.Retrofit",
        };
        for (String cn : probe) {
            try {
                cl.loadClass(cn);
                XposedBridge.log(TAG + ": probe OK   " + cn);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": probe MISS " + cn);
            }
        }

        // 2) LynxView 数据注入点：dump 数据特征（判断 JS 数据是否经 Java 注入）
        try {
            Class<?> lv = cl.loadClass("com.lynx.tasm.LynxView");
            String[] names = {"setTemplateData", "updateData", "setExtraData",
                    "processData", "loadTemplate"};
            for (String m : names) {
                try {
                    XposedBridge.hookAllMethods(lv, m, new LynxDataDiagHook());
                    XposedBridge.log(TAG + ": lynx-data hook " + m);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lynx-data hook skip (" + t.getClass().getSimpleName() + ")");
        }

        // 3) Lynx 列表 item 绑定：dump 节点数据（判断能否从组件拿 uid）
        try {
            Class<?> ad = cl.loadClass("com.lynx.tasm.behavior.ui.list.UIListAdapter");
            XposedBridge.hookAllMethods(ad, "getItem", new LynxItemDiagHook());
            XposedBridge.log(TAG + ": lynx-item hook getItem");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lynx-item hook skip (" + t.getClass().getSimpleName() + ")");
        }

        // 4) okhttp 响应 URL 诊断：定位观看历史用户列表接口（只打印 URL，不读 body）
        try {
            Class<?> respCls = cl.loadClass("okhttp3.Response");
            XposedBridge.hookAllMethods(respCls, "body", new UrlDiagHook());
            XposedBridge.log(TAG + ": okhttp-url hook body()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": okhttp-url hook skip (" + t.getClass().getSimpleName() + ")");
        }
        // 5) TTNet / 字节网络库探测
        String[] netProbe = {
                "com.bytedance.ttnet.http.HttpRequestInfo",
                "com.bytedance.frameworks.baselib.network.http.NetworkParams",
                "com.bytedance.retrofit2.client.Response",
                "com.bytedance.retrofit2.mime.TypedInput",
        };
        for (String cn : netProbe) {
            try {
                cl.loadClass(cn);
                XposedBridge.log(TAG + ": probe OK   " + cn);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": probe MISS " + cn);
            }
        }

        XposedBridge.log(TAG + ": json-user-filter diag ready");

        XposedBridge.log(TAG + ": [disabled] cls dump");
        XposedBridge.log(TAG + ": [disabled] cls dump");
        // 6) DexFile 枚举：扫出 Lynx 网络/观看历史相关类名（启动即打印）
        dumpClassNames(cl, new String[]{"httpservice", "xhr", "httpmodule",
                "lynx.network", "lynxresource", "lynx.env", "lynx.service"});

        // 7) Lynx fetch 模块：打印方法签名，并 hook 回调改响应（JS 数据入口）
        try {
            Class<?> fm = cl.loadClass("com.lynx.jsbridge.LynxFetchModule");
            for (java.lang.reflect.Method m : fm.getDeclaredMethods()) {
                StringBuilder sb = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": fetchm " + m.getName() + "(" + sb + ")");
            }
            // JS 请求入口：包装 Callback（响应经 callback.invoke(JSON) 回到 JS），在投递前过滤隐藏用户
            String[] entries = {"fetch", "request", "requestStreaming"};
            for (String name : entries) {
                try {
                    XposedBridge.hookAllMethods(fm, name, new LynxFetchCallHook());
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": lynx-fetch filter hooks ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lynx-fetch skip " + t.getClass().getSimpleName());
        }

        // 8) LynxView 数据方法 + 文本组件类探测
        try {
            Class<?> lv = cl.loadClass("com.lynx.tasm.LynxView");
            for (java.lang.reflect.Method m : lv.getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("data") || n.contains("global")
                        || n.contains("prop") || n.contains("render")
                        || n.contains("update")) {
                    XposedBridge.log(TAG + ": lynxview-m " + m.getName());
                }
            }
        } catch (Throwable ignored) {
        }
        String[] txtCls = {"com.lynx.tasm.behavior.ui.text.LynxTextView",
                "com.lynx.tasm.behavior.ui.text.LynxTextUI",
                "com.lynx.tasm.behavior.ui.text.UIText",
                "com.lynx.tasm.behavior.ui.view.UIView"};
        for (String cn2 : txtCls) {
            try {
                cl.loadClass(cn2);
                XposedBridge.log(TAG + ": probe OK   " + cn2);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": probe MISS " + cn2);
            }
        }

        // 8.5) Annie HTTP 服务：AnnieX/Hunter 页面（观看历史）的网络入口，包装回调过滤用户列表
        try {
            Class<?> hs = cl.loadClass("com.bytedance.android.annie.service.network.DefaultHttpService");
            for (java.lang.reflect.Method m : hs.getDeclaredMethods()) {
                StringBuilder sb2 = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) sb2.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": annie-http-m " + m.getName() + "(" + sb2 + ")");
                if (m.getParameterTypes().length > 0) {
                    try {
                        XposedBridge.hookAllMethods(hs, m.getName(), new LynxFetchCallHook());
                    } catch (Throwable ignored) {
                    }
                }
            }
            XposedBridge.log(TAG + ": annie-http hooks ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": annie-http skip " + t.getClass().getSimpleName());
        }

        // 8.5) Annie HTTP 服务：AnnieX/Hunter 页面（观看历史）的网络入口，包装回调过滤用户列表
        try {
            Class<?> hs = cl.loadClass("com.bytedance.android.annie.service.network.DefaultHttpService");
            for (java.lang.reflect.Method m : hs.getDeclaredMethods()) {
                StringBuilder sb2 = new StringBuilder();
                for (Class<?> pt : m.getParameterTypes()) sb2.append(pt.getSimpleName()).append(',');
                XposedBridge.log(TAG + ": annie-http-m " + m.getName() + "(" + sb2 + ")");
                if (m.getParameterTypes().length > 0) {
                    try {
                        XposedBridge.hookAllMethods(hs, m.getName(), new LynxFetchCallHook());
                    } catch (Throwable ignored) {
                    }
                }
            }
            XposedBridge.log(TAG + ": annie-http hooks ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": annie-http skip " + t.getClass().getSimpleName());
        }

        // 9) Lynx 页面渲染树隐藏：观看历史「用户」列表按配置昵称定位列表项并隐藏
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "onAttachedToWindow",
                    new LynxAttachHook());
            XposedBridge.log(TAG + ": lynx-view-hide hook onAttachedToWindow");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": lynx-view-hide skip " + t.getClass().getSimpleName());
        }
    }

    /** LynxView 附加到窗口时启动扫描轮询（Lynx 滚动不走 Java，需持续轮询） */
    private static class LynxAttachHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object v = param.thisObject;
                if (v == null) return;
                if (!v.getClass().getName().contains("LynxView")) return;
                startLynxScanner((android.view.View) v);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final java.util.Set<android.view.View> LYNX_SCANNED =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<android.view.View, Boolean>());

    /** 启动 Lynx 渲染树扫描轮询（同一 View 只启动一次） */
    private static void startLynxScanner(android.view.View root) {
        if (root == null) return;
        synchronized (LYNX_SCANNED) {
            if (!LYNX_SCANNED.add(root)) return;
        }
        XposedBridge.log(TAG + ": lynx scanner started "
                + root.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(root)));
        Thread t = new Thread(new LynxPollTask(root));
        t.setDaemon(true);
        t.start();
    }

    /** 轮询扫描：每 800ms 扫一次，页面销毁或超时后停止 */
    private static class LynxPollTask implements Runnable {
        private final android.view.View root;

        LynxPollTask(android.view.View root) {
            this.root = root;
        }

        @Override
        public void run() {
            for (int i = 0; i < 150; i++) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (root.getWindowToken() == null) return;
                    if (!enabled || !visitorHidden || names.isEmpty()) continue;
                    root.post(new LynxHideTask(root));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 遍历 Lynx 渲染树，把显示隐藏昵称的列表项置 GONE */
    private static class LynxHideTask implements Runnable {
        private final android.view.View root;

        LynxHideTask(android.view.View root) {
            this.root = root;
        }

        @Override
        public void run() {
            try {
                java.util.ArrayList<android.widget.TextView> tvs = new java.util.ArrayList<>();
                collectTextViews(root, tvs, 0);
                int hidden = 0;
                for (android.widget.TextView tv : tvs) {
                    CharSequence cs = tv.getText();
                    if (cs == null) continue;
                    String s = cs.toString().trim();
                    if (s.isEmpty() || !names.contains(s)) continue;
                    android.view.View item = findItemContainer(tv);
                    if (item != null && item.getVisibility() == android.view.View.VISIBLE) {
                        item.setVisibility(android.view.View.GONE);
                        hidden++;
                    }
                }
                long now = System.currentTimeMillis();
                if (hidden > 0) {
                    XposedBridge.log(TAG + ": lynx-view-hide hidden " + hidden
                            + " item(s), tvs=" + tvs.size());
                } else if (now - lastLynxDiag > 5000) {
                    lastLynxDiag = now;
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    for (android.widget.TextView tv : tvs) {
                        CharSequence c = tv.getText();
                        if (c == null || c.length() == 0) continue;
                        if (shown++ >= 8) break;
                        sb.append('[').append(c).append(']');
                    }
                    XposedBridge.log(TAG + ": lynx-view-texts n=" + tvs.size() + " " + sb);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 递归收集 TextView（深度限制 24） */
    private static void collectTextViews(android.view.View v,
                                         java.util.List<android.widget.TextView> out, int depth) {
        if (v == null || depth > 24 || out.size() > 400) return;
        if (v.getVisibility() != android.view.View.VISIBLE) return;
        if (v instanceof android.widget.TextView) {
            out.add((android.widget.TextView) v);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTextViews(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** 从文本节点向上找「列表项」容器：宽度接近屏宽、高度足够的第一层 */
    private static android.view.View findItemContainer(android.view.View tv) {
        try {
            int screenW = tv.getResources().getDisplayMetrics().widthPixels;
            int minH = (int) (60 * tv.getResources().getDisplayMetrics().density);
            android.view.View cur = tv;
            for (int i = 0; i < 7 && cur != null; i++) {
                android.view.View parent = (android.view.View) cur.getParent();
                if (parent == null || parent.getClass().getName().startsWith("com.lynx.tasm.LynxView")) {
                    break;
                }
                if (parent.getWidth() >= screenW * 0.8f && parent.getHeight() >= minH) {
                    return parent;
                }
                cur = parent;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在 Lynx fetch 调用前把 Callback 参数换成过滤代理 */
    private static class LynxFetchCallHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                for (int i = 0; i < param.args.length; i++) {
                    Object a = param.args[i];
                    if (a == null || a instanceof String) continue;
                    Class<?>[] ifs = a.getClass().getInterfaces();
                    if (ifs == null || ifs.length == 0) continue;
                    boolean isCb = false;
                    for (Class<?> itf : ifs) {
                        if (itf.getName().contains("Callback")) {
                            isCb = true;
                            break;
                        }
                    }
                    if (!isCb) continue;
                    param.args[i] = java.lang.reflect.Proxy.newProxyInstance(
                            a.getClass().getClassLoader(), ifs, new LynxCallbackProxy(a));
                    long now = System.currentTimeMillis();
                    if (now - lastLynxDiag > 1500) {
                        lastLynxDiag = now;
                        XposedBridge.log(TAG + ": lynx-cb wrapped arg" + i
                                + " " + ifs[0].getSimpleName());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Callback 代理：invoke 里的 JSON 字符串按 uid 过滤后再交给原回调 */
    private static class LynxCallbackProxy implements java.lang.reflect.InvocationHandler {
        private final Object target;

        LynxCallbackProxy(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            try {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (!(args[i] instanceof String)) continue;
                        String s = (String) args[i];
                        if (s.length() < 40 || s.length() > 900000) continue;
                        if (!s.contains("\"nickname\"") || !s.contains("\"uid\"")) continue;
                        Object root;
                        try {
                            root = new org.json.JSONTokener(s).nextValue();
                        } catch (Throwable t) {
                            continue;
                        }
                        int removed = pruneHiddenUsers(root);
                        if (removed > 0) {
                            args[i] = root.toString();
                            XposedBridge.log(TAG + ": lynx-cb pruned " + removed
                                    + " item(s) via " + method.getName());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                return method.invoke(target, args);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Lynx fetch 响应过滤：字符串参数里的用户列表 JSON 按 uid 移除隐藏对象 */
    private static class LynxRespFilterHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                for (int i = 0; i < param.args.length; i++) {
                    Object a = param.args[i];
                    if (!(a instanceof String)) continue;
                    String s = (String) a;
                    if (s.length() < 40 || s.length() > 900000) continue;
                    if (!s.contains("\"nickname\"") || !s.contains("\"uid\"")) continue;
                    Object root;
                    try {
                        root = new org.json.JSONTokener(s).nextValue();
                    } catch (Throwable t) {
                        continue;
                    }
                    int removed = pruneHiddenUsers(root);
                    if (removed > 0) {
                        param.args[i] = root.toString();
                        XposedBridge.log(TAG + ": lynx-resp pruned " + removed
                                + " item(s) via " + param.method.getName());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 遍历 dex 类名，打印含关键词的类（诊断，最多 40 条） */
    private static void dumpClassNames(ClassLoader cl, String[] keywords) {
        try {
            Object pathList = getFieldDeep(cl, "pathList");
            if (pathList == null) {
                XposedBridge.log(TAG + ": cls dump: no pathList");
                return;
            }
            Object[] elements = (Object[]) getFieldDeep(pathList, "dexElements");
            if (elements == null) {
                XposedBridge.log(TAG + ": cls dump: no dexElements");
                return;
            }
            java.util.Set<String> hits = new java.util.TreeSet<>();
            int scanned = 0;
            for (Object el : elements) {
                if (el == null) continue;
                Object dexFile = getFieldDeep(el, "dexFile");
                if (dexFile == null) continue;
                java.util.Enumeration<String> en = null;
                try {
                    java.lang.reflect.Method m = dexFile.getClass().getMethod("entries");
                    en = (java.util.Enumeration<String>) m.invoke(dexFile);
                } catch (Throwable ignored) {
                }
                if (en == null) continue;
                while (en.hasMoreElements()) {
                    String cn = en.nextElement();
                    if (cn == null || cn.length() < 5) continue;
                    scanned++;
                    String lower = cn.toLowerCase();
                    for (String kw : keywords) {
                        if (lower.contains(kw)) {
                            hits.add(cn);
                            break;
                        }
                    }
                }
            }
            int shown = 0;
            for (String cn : hits) {
                XposedBridge.log(TAG + ": cls " + cn);
                if (++shown >= 40) break;
            }
            XposedBridge.log(TAG + ": cls dump scanned=" + scanned + " hits=" + hits.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cls dump failed " + t);
        }
    }

    /** 逐级向上查找字段（兼容字段定义在父类的情况） */
    private static Object getFieldDeep(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** okhttp Response.body() URL 诊断：只匹配访问/历史类接口时打印 URL */
    private static class UrlDiagHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object resp = param.thisObject;
                Object req = XposedHelpers.callMethod(resp, "request");
                Object urlObj = XposedHelpers.callMethod(req, "url");
                String url = String.valueOf(urlObj);
                String lower = url.toLowerCase();
                if (lower.contains("history") || lower.contains("visit")
                        || lower.contains("frequently") || lower.contains("watch")) {
                    long now = System.currentTimeMillis();
                    if (now - lastLynxDiag > 1200) {
                        lastLynxDiag = now;
                        XposedBridge.log(TAG + ": http-url " + url);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** LynxView 数据注入诊断：节流 dump 参数特征 */
    private static class LynxDataDiagHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                // 先于节流处理：页面加载即启动扫描器
                if ("loadTemplate".equals(param.method.getName())
                        && param.thisObject instanceof android.view.View) {
                    startLynxScanner((android.view.View) param.thisObject);
                }
                long now = System.currentTimeMillis();
                if (now - lastLynxDiag < 4000) return;
                lastLynxDiag = now;
                StringBuilder sb = new StringBuilder();
                for (Object a : param.args) {
                    if (a == null) {
                        sb.append("null|");
                        continue;
                    }
                    String s = String.valueOf(a);
                    if (s.length() > 500) s = s.substring(0, 500);
                    sb.append(s).append('|');
                }
                XposedBridge.log(TAG + ": lynx-data " + param.method.getName() + " -> " + sb);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Lynx 列表 item 诊断：dump 节点对象特征 */
    private static class LynxItemDiagHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastLynxDiag < 3000) return;
                lastLynxDiag = now;
                Object r = param.getResult();
                String s = r == null ? "null" : r.getClass().getName() + "@"
                        + Integer.toHexString(System.identityHashCode(r));
                XposedBridge.log(TAG + ": lynx-item getItem(" + param.args[0] + ") = " + s);
            } catch (Throwable ignored) {
            }
        }
    }

    /** okhttp 响应体字符串 → 过滤隐藏用户对象（仅处理像用户列表的 JSON） */
    private static class JsonUserFilterHook extends XC_MethodHook {
        private long lastDiag = 0L;

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!enabled || !visitorHidden || uids.isEmpty()) return;
                Object r = param.getResult();
                if (!(r instanceof String)) return;
                String s = (String) r;
                if (s.length() < 40 || s.length() > 600000) return;
                // 快速特征：同时含昵称与 uid（用户列表），否则跳过（性能保护）
                if (!s.contains("\"nickname\"") || !s.contains("\"uid\"")) return;
                Object root;
                try {
                    root = new org.json.JSONTokener(s).nextValue();
                } catch (Throwable t) {
                    return;
                }
                if (!(root instanceof org.json.JSONObject) && !(root instanceof org.json.JSONArray)) {
                    return;
                }
                int removed = pruneHiddenUsers(root);
                if (removed > 0) {
                    param.setResult(root.toString());
                    XposedBridge.log(TAG + ": json-user-filter pruned " + removed
                            + " item(s), len " + s.length());
                } else if (System.currentTimeMillis() - lastDiag > 6000) {
                    lastDiag = System.currentTimeMillis();
                    String head = s.length() > 600 ? s.substring(0, 600) : s;
                    XposedBridge.log(TAG + ": json-user-diag len=" + s.length() + " head=" + head);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** JSON 节点里是否有字符串/数字值精确等于隐藏 uid（递归） */
    private static boolean jsonContainsHiddenUid(Object node) {
        try {
            if (node instanceof org.json.JSONObject) {
                org.json.JSONObject o = (org.json.JSONObject) node;
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    Object v = o.opt(it.next());
                    if (v instanceof String) {
                        if (isHiddenUid(((String) v).trim())) return true;
                    } else if (v instanceof Number) {
                        if (isHiddenUid(String.valueOf(v))) return true;
                    } else if (v instanceof org.json.JSONObject || v instanceof org.json.JSONArray) {
                        if (jsonContainsHiddenUid(v)) return true;
                    }
                }
            } else if (node instanceof org.json.JSONArray) {
                org.json.JSONArray a = (org.json.JSONArray) node;
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v instanceof String) {
                        if (isHiddenUid(((String) v).trim())) return true;
                    } else if (v instanceof Number) {
                        if (isHiddenUid(String.valueOf(v))) return true;
                    } else if (v instanceof org.json.JSONObject || v instanceof org.json.JSONArray) {
                        if (jsonContainsHiddenUid(v)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 递归移除数组中的隐藏用户对象，返回移除数量 */
    private static int pruneHiddenUsers(Object node) {
        int n = 0;
        if (node instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) node;
            for (int i = arr.length() - 1; i >= 0; i--) {
                Object el = arr.opt(i);
                if (el instanceof org.json.JSONObject && jsonContainsHiddenUid(el)) {
                    arr.remove(i);
                    n++;
                } else if (el instanceof org.json.JSONObject || el instanceof org.json.JSONArray) {
                    n += pruneHiddenUsers(el);
                }
            }
        } else if (node instanceof org.json.JSONObject) {
            org.json.JSONObject obj = (org.json.JSONObject) node;
            java.util.Iterator<String> it = obj.keys();
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = obj.opt(k);
                if (v instanceof org.json.JSONArray || v instanceof org.json.JSONObject) {
                    n += pruneHiddenUsers(v);
                }
            }
        }
        return n;
    }

    /** 元素是否为要隐藏的用户：自身 uid 字段命中，或 user/author 等子对象 uid 命中 */
    private static boolean isHiddenUserItem(Object el) {
        if (!(el instanceof org.json.JSONObject)) return false;
        org.json.JSONObject o = (org.json.JSONObject) el;
        if (uidFieldHidden(o)) return true;
        String[] nested = {"user", "author", "user_info", "userInfo",
                "owner", "from_user", "fromUser", "user_data"};
        for (String k : nested) {
            Object v = o.opt(k);
            if (v instanceof org.json.JSONObject && uidFieldHidden((org.json.JSONObject) v)) {
                return true;
            }
        }
        return false;
    }

    /** JSON 对象里的 uid 类字段是否命中隐藏名单 */
    private static boolean uidFieldHidden(org.json.JSONObject o) {
        String[] keys = {"uid", "user_id", "userId", "uid_str", "uidStr", "user_id_str"};
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == org.json.JSONObject.NULL) continue;
            String sv = String.valueOf(v);
            if (isHiddenUid(sv)) return true;
        }
        return false;
    }

    /**
     * v7.2 需求1：隐藏互动消息页中隐藏联系人的消息。
     * 反编译确认：NotificationAdapterV2 extends BaseAdapter<BaseNotice>，
     * 列表数据经 LLF(List) 过滤后由 LJLLLLLL(List) / LLI(List) 设置。
     * BaseNotice 有 public String userId（getter LJIJ()），findUidDeep 递归能扫到，
     * 因此用 VisitorListFilter 按 uid 判定即可（label 以 "interact" 开头 → 归 interactHidden 开关）。
     * 三个入口都装，覆盖「全量设置」和「DiffUtil 增量」两条路径；非 mutate，天然可逆。
     */
    private static void installInteractNoticeHook(ClassLoader cl) {
        int ok = 0;
        String[] classNames = {
                "com.ss.android.ugc.aweme.socialnotice.view.adapter.NotificationAdapterV2",
                // 老版本类名兜底
                "com.ss.android.ugc.aweme.notification.adapter.NotificationAdapterV2",
        };
        for (String cn : classNames) {
            Class<?> ad = null;
            try {
                ad = XposedHelpers.findClass(cn, cl);
            } catch (Throwable ignored) {
            }
            if (ad == null) continue;
            // 40.8.0 LLF(List):List 自带过滤；41.3.0 LJLJJI(List):List
            ok += hookByArity(ad, new String[]{"LJLJJI", "LLF"}, 1,
                    new VisitorListFilter("interact-ret", -1), "interact-ret");
            // 40.8.0 LJLLLLLL(List) 全量；41.3.0 setData(List)/LJLLILLLL(List)
            ok += hookByArity(ad, new String[]{"setData", "LJLLILLLL", "LJLLLLLL"}, 1,
                    new VisitorListFilter("interact-setdata", 0), "interact-setdata");
            // 40.8.0 LLI(List) 增量；41.3.0 无对应，保留旧名兼容
            ok += hookByArity(ad, new String[]{"LLI"}, 1,
                    new VisitorListFilter("interact-diff", 0), "interact-diff");
            XposedBridge.log(TAG + ": interact-notice hooks installed on " + cn);
            break;
        }
        if (ok == 0) {
            XposedBridge.log(TAG + ": interact-notice NO adapter found (ok=" + ok + ")");
        }
    }

    // ==================== v10：隐藏联系人消息提醒 ====================
    //  1) 应用内横幅 ImInnerPushManagerNew.LJ(BannerInfo,Function1)：命中隐藏联系人则
    //     setResult(null)，横幅完全不弹出。
    //  2) 命中隐藏联系人消息时，在消息页标题「消息」旁置红点提示。
    //  注：系统通知栏由厂商推送进程 heytap.mcs 独立发出，宿主进程 hook 不到，已放弃改写。
    private static boolean notifyHookInstalled = false;

    private static void installNotifyMaskHooks(ClassLoader cl) {
        installBannerMaskHook(cl);
    }

    /** 门控：总开关开、未处于临时显示、且消息提醒开关开时才生效。 */
    private static boolean notifyMaskActive() {
        return enabledSaved && !tempPaused && notifyMaskEnabled;
    }

    /** hook 应用内私信横幅，命中隐藏联系人则拦截不显示。 */
    private static void installBannerMaskHook(ClassLoader cl) {
        XC_MethodHook mask = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!notifyMaskActive()) return;
                    Object banner = param.args.length > 0 ? param.args[0] : null;
                    if (banner == null) return;
                    String reason = bannerHiddenReason(banner);
                    if (reason != null) {
                        param.setResult(null);
                        signalHiddenMessageDot("banner:" + reason);
                        XposedBridge.log(TAG + ": banner masked (" + reason + ")");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": banner mask hook err " + t);
                }
            }
        };
        int ok = 0;
        try {
            Class<?> mgr = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.notification.immessage.ImInnerPushManagerNew", cl);
            // 精确按方法名 LJ + 2 个参数 hook，不硬依赖 BannerInfo/Function1 类名
            for (Method m : mgr.getDeclaredMethods()) {
                if ("LJ".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    try {
                        XposedBridge.hookMethod(m, mask);
                        ok++;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": banner LJ hook one failed: " + t);
                    }
                }
            }
            XposedBridge.log(TAG + ": banner mask hooks installed " + ok);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": banner mask hook install failed: " + t);
        }
    }

    /**
     * 判断横幅数据（LX/1Ue4，即 BannerInfo）是否属于隐藏联系人。
     * 返回命中的原因字符串，未命中返回 null。
     */
    private static String bannerHiddenReason(Object banner) {
        // 1) 会话 ID 包含隐藏 uid（conversation_id 常形如 uid_xxx）
        String conv = tryStr(banner, new String[]{"getConversationId", "LJIJ"});
        if (conv != null && conversationHitsHiddenUid(conv)) return "conversation=" + conv;
        // 2) 从 Message 对象取发送者 uid
        Object msg = getFieldQuiet(banner, "i");
        if (msg == null) msg = getFieldQuiet(banner, "LIZLLL");
        String uid = messageSenderUid(msg);
        if (uid != null && isHiddenUid(uid)) return "uid=" + uid;
        // 3) 标题（昵称）命中隐藏昵称
        String title = tryStr(banner, new String[]{"getTitle", "getDescription"});
        if (title != null && titleHitsHiddenName(title)) return "title=" + title;
        // 4) 字段 F 常是 secUid / uid
        String f = getStrField(banner, "F");
        if (f != null && isHiddenUid(f)) return "secUid=" + f;
        return null;
    }

    /** 从 IM Message 对象里取发送者 uid（多 getter 兜底）。 */
    private static String messageSenderUid(Object msg) {
        if (msg == null) return null;
        String s = tryStr(msg, new String[]{
                "getSenderUid", "getSenderId", "getFromUid", "getUidS",
                "getUid", "getSender", "getSingleId", "LJIJJLI"});
        if (s != null && isHiddenUid(s)) return s;
        // Message 里常嵌套 sender 对象
        Object sender = getFieldQuiet(msg, "sender");
        if (sender == null) sender = getFieldQuiet(msg, "c");
        if (sender != null) {
            String u = tryStr(sender, new String[]{"getUid", "getUidS", "getSingleId", "getId"});
            if (u != null && isHiddenUid(u)) return u;
        }
        return null;
    }

    /** 会话 ID 里是否包含任一隐藏 uid 子串。 */
    private static boolean conversationHitsHiddenUid(String conv) {
        if (conv == null || conv.length() < 5) return false;
        Set<String> u = uids;
        if (u == null) return false;
        for (String id : u) {
            if (id != null && id.length() >= 5 && conv.contains(id)) return true;
        }
        return false;
    }

    /** 通知/横幅标题是否命中隐藏联系人昵称（精确或包含）。 */
    private static boolean titleHitsHiddenName(String title) {
        if (title == null || title.trim().isEmpty()) return false;
        Set<String> hiddenUids = uids;
        if (hiddenUids == null || hiddenUids.isEmpty()) return false;
        // 已保存的昵称集合
        for (String nm : names) {
            if (nm != null && nm.length() >= 1 && title.contains(nm)) return true;
        }
        // 用好友池 uid→昵称反查（老配置可能没保存 name）
        synchronized (FRIENDS) {
            for (Map.Entry<String, String> e : FRIENDS.entrySet()) {
                if (!hiddenUids.contains(e.getKey())) continue;
                String nm = e.getValue();
                if (nm != null && nm.length() >= 2 && title.contains(nm)) return true;
            }
        }
        return false;
    }

    /** 反射读字段（含父类），失败返回 null。 */
    private static Object getFieldQuiet(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 反射读字符串字段。 */
    private static String getStrField(Object obj, String name) {
        Object v = getFieldQuiet(obj, name);
        return v instanceof CharSequence ? v.toString() : null;
    }

    /** 反射写字段（含父类）。 */
    private static void setFieldQuiet(Object obj, String name, Object value) {
        if (obj == null) return;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
    }

    /**
     * v7.0 需求2：主页访客记录隐藏。
     * ProfileVisitorAdapter.LJZI(List<User>, Map, boolean) → LL(list,map,z) 把 User 转成
     * ProfileVisitorStruct → LJLLI(list) 设置数据。hook LL 返回值（元素含 User.uid）按 uid 过滤。
     * 元素 ProfileVisitorStruct 继承 User，有 getUid()/uid 字段，resolveUid 可识别。
     */
    private static void installVisitorHook(ClassLoader cl) {
        int ok = 0;
        try {
            Class<?> ad = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.interaction.profilevisitor.adapter.ProfileVisitorAdapter", cl);
            // 40.8.0: LL(List,Map,boolean) 返回 List；41.3.0: LJLILLLLZI(List,Map,boolean) 返回 List
            ok += hookByArity(ad, new String[]{"LJLILLLLZI", "LL"}, 3,
                    new VisitorListFilter("visitor-convert", -1), "visitor-convert");
            // 40.8.0: LJZI(List,Map,boolean) 入参；41.3.0: LJLIIL(List,Map,boolean) 入参
            ok += hookByArity(ad, new String[]{"LJLIIL", "LJZI"}, 3,
                    new VisitorListFilter("visitor-arg", 0), "visitor-arg");
            // 最终设置数据兜底：40.8.0 LJLLI(List)；41.3.0 setData(List)/addData(List)
            ok += hookByArity(ad, new String[]{"setData", "addData", "LJLLI"}, 1,
                    new VisitorListFilter("visitor-setdata", 0), "visitor-setdata");
            XposedBridge.log(TAG + ": visitor hooks installed " + ok);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": visitor hook setup failed: " + t);
        }
    }

    /**
     * v7.0 需求3：禁止进入隐藏联系人主页/聊天。
     * 不依赖混淆类名：hook Activity.startActivity / startActivityForResult，检查 Intent 中的
     * uid / sec_user_id，命中隐藏名单则拦截（setResult(null) 阻止启动）。
     * User 主页Intent key: sec_user_id / user_id；聊天 SessionInfo 通常带 toUserId / uid。
     */
    private static void installStartActivityGuard(ClassLoader cl) {
        int ok = 0;
        XC_MethodHook guard = new XC_MethodHook() {
            private boolean tryBlock(Object intent, String method) {
                if (!enabled || !blockEnterHidden || !tempAllows("blockEnter")
                        || uids.isEmpty() || intent == null) return false;
                try {
                    Bundle b = (Bundle) XposedHelpers.callMethod(intent, "getExtras");
                    if (b == null) return false;
                    // 诊断：仅当 Bundle 含 uid 类 key 时节流 dump（弄清主页/聊天 Intent 结构）
                    maybeDiagIntent(intent, b);
                    String hit = findHiddenUidInBundle(b);
                    if (hit != null) {
                        XposedBridge.log(TAG + ": blocked enter via " + method + " uid=" + hit);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object intent = param.args.length > 0 ? param.args[0] : null;
                if (tryBlock(intent, "startActivity")) {
                    param.setResult(null);
                }
            }
        };
        try {
            Class<?> act = XposedHelpers.findClass("android.app.Activity", cl);
            XposedHelpers.findAndHookMethod(act, "startActivity", Intent.class, guard);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": guard startActivity hook failed: " + t);
        }
        try {
            Class<?> act = XposedHelpers.findClass("android.app.Activity", cl);
            XposedHelpers.findAndHookMethod(act, "startActivityForResult",
                    Intent.class, int.class, guard);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": guard startActivityForResult hook failed: " + t);
        }
        // ContextImpl 兜底（部分跳转从非 Activity 上下文发起）
        try {
            Class<?> ci = XposedHelpers.findClass("android.app.ContextImpl", cl);
            XposedHelpers.findAndHookMethod(ci, "startActivity", Intent.class, Bundle.class, guard);
            ok++;
        } catch (Throwable t) {
        }
        XposedBridge.log(TAG + ": start-activity guard hooks installed " + ok);
    }

    /**
     * v7.2 需求3补强：抖音大量跳转走 SmartRouter(scheme) 路由，不经 Activity.startActivity，
     * 所以仅靠 installStartActivityGuard 拦不住「发私信进聊天 / 点头像进聊天」。
     * 兜底：hook Activity.onCreate / onNewIntent，在页面真正创建后检查其 getIntent()，
     * 若目标页面是聊天/主页类，且 Intent 的 extras / data URI 命中隐藏 uid，立即 finish()。
     * 只拦截聊天/主页类 Activity，避免误伤普通页面。
     */
    private static void installActivityEnterGuard(ClassLoader cl) {
        XC_MethodHook guard = new XC_MethodHook() {
            private void maybeBlock(MethodHookParam param, Object intent) {
                if (!enabled || !blockEnterHidden || !tempAllows("blockEnter")
                        || uids.isEmpty()) return;
                if (intent == null) return;
                String act = param.thisObject == null ? "?"
                        : param.thisObject.getClass().getName();
                // 只拦聊天/会话/主页类页面，避免误伤
                String low = act.toLowerCase();
                boolean target = low.contains("chat") || low.contains("im")
                        || low.contains("session") || low.contains("profile")
                        || low.contains("user") || low.contains("conversation")
                        || low.contains("message");
                if (!target) return;
                try {
                    // 诊断：节流 dump 目标 Activity 的 Intent，弄清聊天页 uid 藏在哪
                    maybeDiagActivity(act, intent);
                    String hit = findHiddenUidInIntent(intent);
                    if (hit != null) {
                        XposedBridge.log(TAG + ": blocked enter via activity " + act + " uid=" + hit);
                        try {
                            XposedHelpers.callMethod(param.thisObject, "finish");
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object intent = XposedHelpers.callMethod(param.thisObject, "getIntent");
                    if (intent != null) maybeBlock(param, intent);
                } catch (Throwable ignored) {
                }
            }
        };
        int ok = 0;
        try {
            Class<?> act = XposedHelpers.findClass("android.app.Activity", cl);
            XposedHelpers.findAndHookMethod(act, "onCreate", Bundle.class, guard);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": enter guard onCreate failed: " + t);
        }
        try {
            Class<?> act = XposedHelpers.findClass("android.app.Activity", cl);
            XposedHelpers.findAndHookMethod(act, "onNewIntent", Intent.class, guard);
            ok++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": enter guard onNewIntent failed: " + t);
        }
        XposedBridge.log(TAG + ": activity enter guard hooks installed " + ok);
    }

    /** 在 Intent 的 extras + data URI 里找隐藏 uid（含子串匹配，兼容 conversation_id 形式）。 */
    private static String findHiddenUidInIntent(Object intent) {
        if (intent == null) return null;
        try {
            Bundle b = (Bundle) XposedHelpers.callMethod(intent, "getExtras");
            String hit = findHiddenUidInBundle(b);
            if (hit != null) return hit;
        } catch (Throwable ignored) {
        }
        try {
            Object u = XposedHelpers.callMethod(intent, "getData");
            if (u != null) {
                String hit = containsHiddenUid(String.valueOf(u));
                if (hit != null) return hit;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 节流：记录聊天/主页 Activity 的 Intent 结构（extras + data），用于确认 uid 藏在哪。 */
    private static void maybeDiagActivity(String act, Object intent) {
        long now = System.currentTimeMillis();
        if (now - lastActivityDiag < 2500) return;
        String low = act.toLowerCase();
        if (!(low.contains("chat") || low.contains("session") || low.contains("profile")
                || low.contains("user") || low.contains("conversation"))) return;
        lastActivityDiag = now;
        StringBuilder sb = new StringBuilder();
        try {
            Bundle b = (Bundle) XposedHelpers.callMethod(intent, "getExtras");
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v;
                    try { v = b.get(k); } catch (Throwable ignored) { continue; }
                    // v7.3：对 key_session_info 等对象字段做反射 dump，确认 uid 藏在哪
                    if (v != null && !(v instanceof CharSequence) && !(v instanceof Number)
                            && !(v instanceof Bundle) && (k.contains("session") || k.contains("user")
                            || k.contains("uid") || k.contains("conversation"))) {
                        StringBuilder od = new StringBuilder();
                        dumpObjectFields(v, od, 0);
                        sb.append(k).append('=').append(od).append(' ');
                        continue;
                    }
                    String s = v == null ? "null" : v.toString();
                    if (s.length() > 140) s = s.substring(0, 140) + "…";
                    sb.append(k).append('=').append(s).append(' ');
                }
            }
        } catch (Throwable ignored) {
        }
        String data = "";
        try {
            Object u = XposedHelpers.callMethod(intent, "getData");
            if (u != null) data = String.valueOf(u);
            if (data.length() > 200) data = data.substring(0, 200) + "…";
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + ": act-diag " + act + " data=" + data + " extras{" + sb + "}");
    }

    /**
     * 诊断：仅当 Intent 含 uid/sec_user_id 类 key 时节流输出其目标组件 + 全部 extras，
     * 用于确定「点击隐藏联系人进主页/聊天」时 Intent 的真实结构（secUid vs 数字 uid）。
     */
    private static void maybeDiagIntent(Object intent, Bundle b) {
        long now = System.currentTimeMillis();
        if (now - lastEnterDiag < 2500) return;
        String comp = "?";
        try {
            Object c = XposedHelpers.callMethod(intent, "getComponent");
            if (c != null) comp = String.valueOf(c);
        } catch (Throwable ignored) {
        }
        // 放宽：主页/聊天/会话/IM 类组件，或含 uid 类 key，都 dump（弄清聊天 Intent 结构）
        String compLow = comp.toLowerCase();
        boolean compInteresting = compLow.contains("chat") || compLow.contains("im")
                || compLow.contains("session") || compLow.contains("profile")
                || compLow.contains("user") || compLow.contains("message");
        String[] watch = {"sec_user_id", "user_id", "uid", "to_user_id", "toUserId",
                "from_user_id", "author_uid", "authorUid", "session_id", "sessionId",
                "conversation_id", "chat_id", "chatId"};
        boolean keyInteresting = false;
        for (String k : watch) {
            try {
                if (b.get(k) != null) { keyInteresting = true; break; }
            } catch (Throwable ignored) {
            }
        }
        if (!compInteresting && !keyInteresting) return;
        lastEnterDiag = now;
        StringBuilder sb = new StringBuilder();
        try {
            for (String k : b.keySet()) {
                Object val;
                try { val = b.get(k); } catch (Throwable ignored) { continue; }
                String s = val == null ? "null" : val.toString();
                if (s.length() > 120) s = s.substring(0, 120) + "…";
                sb.append(k).append('=').append(s).append(' ');
            }
        } catch (Throwable ignored) {
        }
        // data URI（IM 跳转常用 scheme）
        String data = "";
        try {
            Object u = XposedHelpers.callMethod(intent, "getData");
            if (u != null) data = String.valueOf(u);
            if (data.length() > 160) data = data.substring(0, 160) + "…";
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + ": enter-diag comp=" + comp + " data=" + data
                + " extras{" + sb + "}");
    }

    /**
     * 在 Bundle 的 uid/sec_user_id 类 key 里找隐藏 uid。
     * 只认已经在隐藏名单里的 uid 值，不匹配昵称。
     */
    private static String findHiddenUidInBundle(Bundle b) {
        if (b == null) return null;
        String[] keys = {"sec_user_id", "user_id", "uid", "to_user_id", "toUserId",
                "from_user_id", "author_uid", "authorUid", "SEC_USER_ID", "USER_ID",
                "session_id", "sessionId", "conversation_id", "chat_id"};
        // 第一轮：key 值精确等于隐藏 uid
        for (String k : keys) {
            try {
                String v = b.getString(k);
                if (isHiddenUid(v)) return v;
            } catch (Throwable ignored) {
            }
        }
        // 第二轮：所有字符串值，精确命中 或 含隐藏 uid 子串（sessionId 形如 "<uid>_x" / "xxx_<uid>"）
        try {
            java.util.Set<String> ks = b.keySet();
            for (String k : ks) {
                Object val;
                try {
                    val = b.get(k);
                } catch (Throwable ignored) {
                    continue;
                }
                if (val instanceof CharSequence) {
                    String s = val.toString();
                    if (isHiddenUid(s)) return s;
                    // 子串匹配：仅当值里包含某个隐藏 uid（>=8位数字，降低误伤）
                    String hit = containsHiddenUid(s);
                    if (hit != null) return hit;
                } else if (val instanceof Bundle) {
                    String hit = findHiddenUidInBundle((Bundle) val);
                    if (hit != null) return hit;
                } else if (val != null) {
                    // v7.3：聊天页 Intent 的关键字段是 Parcelable 对象（key_session_info=SessionInfo），
                    // uid 藏在其对象字段里，不是 String/Bundle。递归反射深挖（精确+子串）。
                    String hit = findHiddenUidInObject(val, 0);
                    if (hit != null) return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * v7.3：递归深挖任意对象（如 ChatRoomActivity 的 SessionInfo）里的隐藏 uid。
     * 字符串精确等于隐藏 uid → 命中；或包含隐藏 uid 子串（conversation_id 形如 "uid_other"）→ 命中。
     * DENY_TYPES 防止钻进系统大对象；depth 限深避免栈溢出。
     */
    private static String findHiddenUidInObject(Object obj, int depth) {
        if (obj == null || depth > 5) return null;
        Class<?> c = obj.getClass();
        String cn = c.getName();
        if (c.isArray() || c.isPrimitive() || DENY_TYPES.contains(cn)) return null;
        // 基础类型/字符串
        if (obj instanceof CharSequence) {
            String s = obj.toString();
            if (isHiddenUid(s)) return s;
            return containsHiddenUid(s);
        }
        if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character) return null;
        // Bundle 交给已有逻辑
        if (obj instanceof Bundle) return findHiddenUidInBundle((Bundle) obj);
        // 集合
        if (obj instanceof java.util.Collection) {
            int n = 0;
            for (Object e : (java.util.Collection<?>) obj) {
                if (e == null) continue;
                String hit = findHiddenUidInObject(e, depth + 1);
                if (hit != null) return hit;
                if (++n >= 20) break;
            }
            return null;
        }
        if (obj instanceof java.util.Map) {
            for (Object e : ((java.util.Map<?, ?>) obj).values()) {
                if (e == null) continue;
                String hit = findHiddenUidInObject(e, depth + 1);
                if (hit != null) return hit;
            }
            return null;
        }
        // 普通对象：反射所有字段
        Field[] fs = getFieldsCached(c);
        for (Field f : fs) {
            try {
                Object v = f.get(obj);
                if (v == null || v == obj) continue;
                String hit = findHiddenUidInObject(v, depth + 1);
                if (hit != null) return hit;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 字符串里是否包含某个隐藏 uid（数字串 >= 8 位）作为子串。 */
    private static String containsHiddenUid(String s) {
        if (s == null || s.length() < 8) return null;
        for (String uid : uids) {
            if (uid == null || uid.length() < 8) continue;
            if (s.indexOf(uid) >= 0) return uid;
        }
        return null;
    }

    private static void installAdapterSpy(ClassLoader cl) {
        try {
            Class<?> rv = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", cl);
            Class<?> adapterCls = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", cl);
            XposedHelpers.findAndHookMethod(rv, "setAdapter", adapterCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object a = param.args[0];
                                if (a == null) return;
                                String cn = a.getClass().getName();
                                String ctxName = "?";
                                try {
                                    Object ctx = XposedHelpers.callMethod(param.thisObject, "getContext");
                                    if (ctx != null) ctxName = ctx.getClass().getName();
                                } catch (Throwable ignored) {
                                }
                                synchronized (ADAPTER_SEEN) {
                                    if (!ADAPTER_SEEN.add(cn + "@" + ctxName)) return;
                                }
                                XposedBridge.log(TAG + ": setAdapter " + cn + " ctx=" + ctxName);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": adapter spy installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": adapter spy failed: " + t);
        }
    }

    private static void installSessionHook(ClassLoader cl) {
        try {
            Class<?> base = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.BaseSessionListAdapter", cl);
            // 40.8.0: LLD(List,boolean)/LJLLLLLL(List)；41.3.0: LJLILLLLZI(List,boolean)/setData(List)
            hookFirst(base, new String[]{"LJLILLLLZI", "LLD"}, new Class[]{List.class, boolean.class}, new LldHook(), "session-ld");
            hookFirst(base, new String[]{"setData", "justSetData", "LJLLLLLL"}, new Class[]{List.class}, new LjlHook(), "session-ljl");
            XposedBridge.log(TAG + ": session hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": session hook install failed: " + t);
        }
    }

    private static class LldHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            filterList(param, 0, "session");
        }
    }

    private static class LjlHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            filterList(param, 0, "session");
        }
    }

    /**
     * 版本兼容辅助：按候选方法名顺序尝试 hook，第一个成功即返回。
     * 用于应对抖音每版混淆方法名变化（如 LLD→LJLILLLLZI）。
     */
    private static int hookFirst(Class<?> cls, String[] names, Class<?>[] params,
                                 XC_MethodHook hook, String label) {
        for (String nm : names) {
            try {
                XposedHelpers.findAndHookMethod(cls, nm, params, hook);
                XposedBridge.log(TAG + ": hookFirst " + label + " -> " + nm);
                return 1;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": hookFirst " + label + " ALL FAILED ("
                + java.util.Arrays.toString(names) + ")");
        return 0;
    }

    /**
     * 版本兼容辅助 2：按方法名 + 参数个数反射匹配（不校验参数类型）。
     * 用于参数类型是短混淆类、findAndHookMethod 因 Class 不一致失败的场景。
     */
    private static int hookByArity(Class<?> cls, String[] names, int paramCount,
                                   XC_MethodHook hook, String label) {
        int n = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            boolean nameOk = false;
            for (String nm : names) if (nm.equals(m.getName())) { nameOk = true; break; }
            if (!nameOk || m.getParameterTypes().length != paramCount) continue;
            try {
                XposedBridge.hookMethod(m, hook);
                XposedBridge.log(TAG + ": hookByArity " + label + " -> " + m.getName());
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n == 0) XposedBridge.log(TAG + ": hookByArity " + label + " NONE");
        return n;
    }

    /** 通用列表 + 关注/粉丝/分享 的统一过滤。 */
    private static class GenericListHook extends XC_MethodHook {
        private final String label;
        private final int argIdx;

        GenericListHook(String label) {
            this(label, 0);
        }

        GenericListHook(String label, int argIdx) {
            this.label = label;
            this.argIdx = argIdx;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            filterList(param, argIdx, label);
        }
    }

    private static void installGenericHooks(ClassLoader cl) {
        // 1) 通用列表适配器（搜索结果等）
        try {
            Class<?> base = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.common.adapter.BaseAdapter", cl);
            // 40.8.0: LJLLI/LJLLLLLL/LJLZ/LJZ；41.3.0: setData/setDataAfterLoadMore/setDataAfterLoadLatest
            String[] methods = {"setData", "setDataAfterLoadMore", "setDataAfterLoadLatest",
                    "LJLLI", "LJLLLLLL", "LJLZ", "LJZ"};
            int ok = 0;
            for (String m : methods) {
                try {
                    XposedHelpers.findAndHookMethod(base, m, List.class,
                            new GenericListHook("base-adapter:" + m));
                    ok++;
                } catch (Throwable t) {
                }
            }
            XposedBridge.log(TAG + ": BaseAdapter hooks installed " + ok + "/" + methods.length);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": BaseAdapter hook failed: " + t);
        }

        // 1a) 【v6.7 搜索源头】粉丝/关注页搜索的最上游数据源：
        //     LocalBasicUserServiceImpl.LJIIIZ(keyword, scene) -> C241510oD.LIZJ() 本地搜索缓存，
        //     返回 List<C241410oC>（元素有真实 uid 字段 LJIJ()）。
        //     下游 FollowerSearchAdapter.LJLLLLLL 只收到 X.10oM 空壳（footer），
        //     dB/BaseAdapter 均拿不到真实联系人，故必须在数据源头按 uid 过滤。
        //     非 mutate：直接 setResult 新列表，开关关闭时原样返回，天然可逆。
        // 门面类与实现类都 hook，确保命中 fragment 的直接调用。
        // 整个块必须吞掉所有 Throwable：任何查找失败都不能中断后续 hook 的安装。
        // 不硬编码混淆参数类 X.EnumC239010no；改为反射遍历方法按名字+参数个数 hook，
        // 避免类未加载/混淆名变化导致 hook 失败。
        try {
            final XC_MethodHook lsHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        boolean on = enabled && !uids.isEmpty()
                                && isFeatureOn(featureOf("follower-localsearch"));
                        Object r = param.getResult();
                        if (!on || !(r instanceof List)) return;
                        List<?> list = (List<?>) r;
                        if (list.isEmpty()) return;
                        ArrayList<Object> keep = new ArrayList<>(list.size());
                        int hit = 0;
                        for (Object item : list) {
                            if (isHidden(item)) hit++;
                            else keep.add(item);
                        }
                        if (System.currentTimeMillis() - lastRelSearchDiag > 2000) {
                            lastRelSearchDiag = System.currentTimeMillis();
                            String t0 = list.isEmpty() ? "-" : list.get(0).getClass().getName();
                            String u0 = list.isEmpty() ? "-" : String.valueOf(resolveUid(list.get(0)));
                            String kw = param.args.length > 0 ? String.valueOf(param.args[0]) : "?";
                            // 全量 dump 第一个元素的字段：类型+字段名=值，定位 uid/昵称藏在哪
                            StringBuilder dump = new StringBuilder();
                            if (!list.isEmpty()) dumpObjectFields(list.get(0), dump, 0);
                            XposedBridge.log(TAG + ": localsearch-diag ["
                                    + (param.thisObject == null ? "?" : param.thisObject.getClass().getSimpleName())
                                    + "] kw=" + kw + " size=" + list.size() + " hit=" + hit
                                    + " type0=" + t0 + " uid0=" + u0
                                    + " poolSize=" + FRIENDS.size() + " uids=" + uids
                                    + " dump=" + dump);
                        }
                        if (hit > 0) {
                            param.setResult(keep);
                            XposedBridge.log(TAG + ": filtered " + hit
                                    + " item(s) [follower-localsearch]");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": localsearch hook error: " + t);
                    }
                }
            };
            String[] lsClasses = {
                    "com.ss.android.ugc.aweme.framework.services.LocalBasicUserService",
                    "com.ss.android.ugc.aweme.services.LocalBasicUserServiceImpl",
            };
            int lsOk = 0;
            for (String cn : lsClasses) {
                try {
                    Class<?> impl = XposedHelpers.findClass(cn, cl);
                    for (java.lang.reflect.Method m : impl.getDeclaredMethods()) {
                        if (!"LJIIIZ".equals(m.getName())) continue;
                        if (m.getParameterTypes().length != 2) continue;
                        XposedBridge.hookMethod(m, lsHook);
                        lsOk++;
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": localsearch hook failed [" + cn + "]: " + t);
                }
            }
            XposedBridge.log(TAG + ": follower-localsearch hooks installed " + lsOk);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": follower-localsearch setup failed: " + t);
        }

        // 1b) 粉丝页搜索结果：FollowerSearchAdapter.LJLLLLLL(List<C241410oC>)
        // 数据来自 LocalBasicUserService.LJIIIZ(关键词)，经该 adapter 设置，不经 dB/KA。
        // 元素 C241410oC 带 uid 字段，isHidden 按 uid 命中。归 relation 开关。
        try {
            Class<?> searchAd = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.following.ui.adapter.FollowerSearchAdapter", cl);
            XposedHelpers.findAndHookMethod(searchAd, "LJLLLLLL", List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object a0 = param.args.length > 0 ? param.args[0] : null;
                                if (!(a0 instanceof List)) {
                                    diagFollowerSearch("arg-not-list");
                                    return;
                                }
                                List<?> list = (List<?>) a0;
                                boolean on = enabled && !uids.isEmpty()
                                        && isFeatureOn(featureOf("follower-search"));
                                ArrayList<Object> keep = new ArrayList<>(list.size());
                                int hit = 0;
                                for (Object item : list) {
                                    if (on && isHidden(item)) hit++;
                                    else keep.add(item);
                                }
                                if (System.currentTimeMillis() - lastRelSearchDiag > 6000) {
                                    lastRelSearchDiag = System.currentTimeMillis();
                                    StringBuilder types = new StringBuilder();
                                    for (Object it : list) {
                                        if (types.length() > 0) types.append("|");
                                        if (it == null) { types.append("null"); continue; }
                                        types.append(it.getClass().getSimpleName());
                                        String uu = resolveUid(it);
                                        types.append("(uid=").append(uu == null ? "-" : uu).append(")");
                                    }
                                    XposedBridge.log(TAG + ": fs-diag size=" + list.size()
                                            + " on=" + on + " hit=" + hit
                                            + " types=" + types);
                                }
                                if (hit > 0) {
                                    param.args[0] = keep;
                                    XposedBridge.log(TAG + ": filtered " + hit
                                            + " item(s) [follower-search-adapter]");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": follower-search hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": follower-search adapter hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": follower-search adapter hook failed: " + t);
        }

        // 2) 关注列表（FollowingRelationViewModel 子类的 HA）
        try {
            Class<?> folIt = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.following.model.FollowingItemList", cl);
            String[] vms = {
                    "com.ss.android.ugc.aweme.following.ui.viewmodel.SelfFollowingRelationViewModel",
                    "com.ss.android.ugc.aweme.following.ui.viewmodel.OtherFollowingRelationViewModel",
            };
            int ok = 0;
            for (String cn : vms) {
                try {
                    Class<?> vm = XposedHelpers.findClass(cn, cl);
                    // 40.8.0 HA；41.3.0 MF(List,FollowingItemList,boolean)，按参数个数反射
                    ok += hookByArity(vm, new String[]{"MF", "HA"}, 3,
                            new GenericListHook("following"), "following-" + cn);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": " + cn + " HA failed: " + t);
                }
            }
            XposedBridge.log(TAG + ": following HA hooks installed " + ok + "/2");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": following hooks failed: " + t);
        }

        // 3) 粉丝列表（FollowerRelationViewModel 子类的列表方法）
        // 40.8.0 = KA(List,X.10js,boolean)；41.3.0 = OF(List,X.1P7u,boolean)
        String jsName = DexClassMap.resolve("search_result_model", "X.1P7u");
        try {
            Class<?> js = XposedHelpers.findClass(jsName, cl);
            String[] vms = {
                    "com.ss.android.ugc.aweme.following.ui.viewmodel.SelfFollowerRelationViewModel",
                    "com.ss.android.ugc.aweme.following.ui.viewmodel.OtherFollowerRelationViewModel",
            };
            int ok = 0;
            for (String cn : vms) {
                try {
                    Class<?> vm = XposedHelpers.findClass(cn, cl);
                    // 按方法名+3参数反射 hook（规避短混淆参数类 Class 不一致）
                    ok += hookByArity(vm, new String[]{"OF", "KA"}, 3,
                            new GenericListHook("follower"), "follower-" + cn);
                } catch (Throwable t) {
                }
            }
            XposedBridge.log(TAG + ": follower list hooks installed " + ok + "/2");
            DexClassMap.recordHook("search_result_model", "搜索结果模型(KA参数)",
                    "X.10js", jsName, ok > 0, "list(List," + jsName + ",boolean) x" + ok + "/2");
        } catch (Throwable t) {
            DexClassMap.recordHook("search_result_model", "搜索结果模型(KA参数)",
                    "X.10js", jsName, false, t.getClass().getSimpleName());
            XposedBridge.log(TAG + ": follower hooks failed: " + t);
        }

        // 3b) 粉丝/关注页「搜索」：mB(String)->dB(Function1)->TA().setValue(...)
        // 搜索走 LiveData state 本地变换，完全绕过 KA，所以隐藏联系人会被带回来。
        // hook 父类 dB，在 after 阶段对最终 state 的展示 list 原地按 uid 过滤。
        try {
            Class<?> baseVm = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.following.ui.viewmodel.FollowerRelationViewModel", cl);
            Class<?> fnCls = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl);
            XposedHelpers.findAndHookMethod(baseVm, "dB", fnCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    filterRelationState(param.thisObject, "follower-search-dB");
                }
            });
            XposedBridge.log(TAG + ": follower-search dB hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": follower-search dB hook failed: " + t);
        }

        // 4) 分享面板联系人
        try {
            Class<?> vm = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.share.IMSharePanelViewModel", cl);
            // 40.8.0 IA(List)；41.3.0 NF(List)
            hookFirst(vm, new String[]{"NF", "IA"}, new Class[]{List.class},
                    new GenericListHook("share"), "share-list");
            XposedBridge.log(TAG + ": share hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share hook failed: " + t);
        }

        // 5) 分享面板头像横条（视频分享页 / 直播间长按分享）
        try {
            String[] headAdapters = {
                    "com.ss.android.ugc.aweme.im.sdk.share.panel.adapter.v2.SharePanelHeadAdapterV2",
                    "com.ss.android.ugc.aweme.im.share.api.sharepanel.SharePanelHeadAdapterV2DowngradeImpl",
            };
            int ok2 = 0;
            for (String cn : headAdapters) {
                try {
                    Class<?> ad = XposedHelpers.findClass(cn, cl);
                    // 40.8.0 LJLLLLLL(List,boolean)；41.3.0 getData():List
                    if (hookFirst(ad, new String[]{"LJLLLLLL"},
                            new Class[]{List.class, boolean.class},
                            new GenericListHook("share-head"), "share-head-arg") == 0) {
                        try {
                            XposedHelpers.findAndHookMethod(ad, "getData",
                                    new ReturnListFilter("share-head-getData"));
                        } catch (Throwable ignored) {
                        }
                    }
                    ok2++;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": " + cn + " head failed: " + t);
                }
            }
            XposedBridge.log(TAG + ": share-head hooks installed " + ok2 + "/2");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-head hooks failed: " + t);
        }

        // 7) 搜索状态层：X.0p04.LJ(String, List, X.0ozx) —— 搜索结果/默认页"更多聊天"数据
        // 41.3.0: X.0Zmq.LJ(String,List,X.0Zn0)，方法名 LJ 未变，仅类名变
        String srcName = DexClassMap.resolve("im_search_source", "X.0Zmq");
        String loadName = DexClassMap.resolve("load_state", "X.0Zn0");
        try {
            Class<?> state = XposedHelpers.findClass(srcName, cl);
            Class<?> ozx = XposedHelpers.findClass(loadName, cl);
            XposedHelpers.findAndHookMethod(state, "LJ",
                    String.class, List.class, ozx, new GenericListHook("search-state", 1));
            XposedBridge.log(TAG + ": search-state hook installed");
            DexClassMap.recordHook("im_search_source", "IM 搜索源",
                    "X.0p04", srcName, true, "LJ(String,List," + loadName + ")");
            DexClassMap.recordHook("load_state", "加载状态模型",
                    "X.0ozx", loadName, true, "LJ 第3参数");
        } catch (Throwable t) {
            DexClassMap.recordHook("im_search_source", "IM 搜索源",
                    "X.0p04", srcName, false, t.getClass().getSimpleName());
            DexClassMap.recordHook("load_state", "加载状态模型",
                    "X.0ozx", loadName, false, t.getClass().getSimpleName());
            XposedBridge.log(TAG + ": search-state hook failed: " + t);
        }

        // 9) 分享面板联系人列表（RecyclerView 版，视频分享页/直播间分享）
        try {
            Class<?> listAdapter = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.share.sharelist.IMShareContactsListAdapter", cl);
            // 40.8.0 LLD(List)；41.3.0 LJLLLL(List)/LJLJJI(List)
            hookFirst(listAdapter, new String[]{"LJLLLL", "LJLJJI", "LLD"}, new Class[]{List.class},
                    new GenericListHook("share-list"), "share-list");
            XposedBridge.log(TAG + ": share-list hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-list hook failed: " + t);
        }

        // 10) 分享面板 ViewModel：EA() 直接返回联系人列表给 UI
        try {
            Class<?> vm2 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.share.IMSharePanelViewModel", cl);
            // 40.8.0 EA():List；41.3.0 IF():List
            XposedHelpers.findAndHookMethod(vm2, "IF", new ReturnListFilter("share-vm-IF"));
            XposedHelpers.findAndHookMethod(vm2, "EA", new ReturnListFilter("share-vm-EA"));
            XposedBridge.log(TAG + ": share-vm list hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-vm EA hook failed: " + t);
        }

        // 11) IM Picker 选人面板（直播间长按分享等）：PickerAdapter.LLD(List, Map)
        try {
            Class<?> picker = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.picker.rips.list.content.PickerAdapter", cl);
            // 40.8.0 LLD(List,Map)；41.3.0 LJLJI(List,Map)
            hookFirst(picker, new String[]{"LJLJI", "LLD"}, new Class[]{List.class, java.util.Map.class},
                    new GenericListHook("picker"), "picker");
            XposedBridge.log(TAG + ": picker hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": picker hook failed: " + t);
        }

        // 12) 信息流长按面板（masklayer2.OptionsDialogV2，含直播间长按分享联系人行）
        try {
            Class<?> od = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.feed.ui.masklayer2.OptionsDialogV2", cl);
            // 40.8.0 LJLLLLLL(List)；41.3.0 LJLL(List)
            hookFirst(od, new String[]{"LJLL", "LJLLLLLL"}, new Class[]{List.class},
                    new GenericListHook("mask-list"), "mask-list");
            try { XposedHelpers.findAndHookMethod(od, "LJZI", new ReturnListFilter("mask-getList")); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(od, "LLFF", new ReturnListFilter("mask-getList")); } catch (Throwable ignored) {}
            XposedBridge.log(TAG + ": mask hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": mask hooks failed: " + t);
        }

        // 13) 互关页（mate.matelist）：MateListDataVM + MateListSimpleSortVM
        try {
            Class<?> vm3 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.mate.matelist.list.MateListDataVM", cl);
            Class<?> cont = XposedHelpers.findClass("kotlin.coroutines.Continuation", cl);
            // 40.8.0 MA(String,List,Continuation)；41.3.0 QF(String,List,Continuation)
            hookFirst(vm3, new String[]{"QF", "MA"}, new Class[]{String.class, List.class, cont},
                    new GenericListHook("mate", 1), "mate-load");
            // 40.8.0 HA()/IA()；41.3.0 MF()/NF()（均无参返回 List）
            for (String m : new String[]{"MF", "NF", "HA", "IA"}) {
                try {
                    XposedHelpers.findAndHookMethod(vm3, m, new ReturnListFilter("mate-" + m));
                } catch (Throwable t) {
                }
            }
            XposedBridge.log(TAG + ": mate hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": mate hooks failed: " + t);
        }

        // 13b) 互关页排序分组：40.8.0 HA(List,String,Map,X.07WK)；41.3.0 MF(List,String,Map,X.06CG)
        String wkName = DexClassMap.resolve("user_comparable", "X.06CG");
        try {
            Class<?> sortVm = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.mate.matelist.list.simplesort.MateListSimpleSortVM", cl);
            Class<?> wk = XposedHelpers.findClass(wkName, cl);
            hookFirst(sortVm, new String[]{"MF", "HA"},
                    new Class[]{List.class, String.class, java.util.Map.class, wk},
                    new GenericListHook("mate-sort", 0), "mate-sort");
            XposedBridge.log(TAG + ": mate-sort hook installed");
            DexClassMap.recordHook("user_comparable", "用户排序比较器",
                    "X.07WK", wkName, true, "HA(List,String,Map," + wkName + ")");
        } catch (Throwable t) {
            DexClassMap.recordHook("user_comparable", "用户排序比较器",
                    "X.07WK", wkName, false, t.getClass().getSimpleName());
            XposedBridge.log(TAG + ": mate-sort hook failed: " + t);
        }

        // 24e) 数据层采集：RawFamiliarFriendsViewModel.UA/ZA(List) 直接记录整份好友列表
        try {
            Class<?> vm = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.friends.viewmodel.RawFamiliarFriendsViewModel", cl);
            XC_MethodHook rec = new ViewModelListRecordHook();
            // 40.8.0 UA/ZA(List)；41.3.0 XF/aG(List)
            for (String m : new String[]{"XF", "aG", "UA", "ZA"}) {
                try {
                    XposedHelpers.findAndHookMethod(vm, m, List.class, rec);
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": familiar-vm record hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": familiar-vm record hook failed: " + t);
        }

        // 24j) 关系全量采集：RelationFetchManager.LJIILJJIL(J, RelationFetchResponse)
        //      服务端关系同步的投递点，App 启动/IM 登录即触发，一次拿到全量好友
        try {
            Class<?> fm = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.relations.core.fetch.RelationFetchManager", cl);
            Class<?> resp = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.relations.core.fetch.model.RelationFetchResponse", cl);
            // 40.8.0 LJIILJJIL(J,resp)；41.3.0 LJIILL(J,resp)
            hookFirst(fm, new String[]{"LJIILL", "LJIILJJIL"}, new Class[]{long.class, resp},
                    new RelationFetchHook(), "relation-fetch");
            XposedBridge.log(TAG + ": relation-fetch record hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": relation-fetch record hook failed: " + t);
        }

        // 24i) 互关页适配器数据提交：RawFamiliarFriendsAdapter.LJLLLLLL(List)
        try {
            Class<?> ad = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.friends.adapter.RawFamiliarFriendsAdapter", cl);
            // 40.8.0 LJLLLLLL(List)；41.3.0 setData(List)
            hookFirst(ad, new String[]{"setData", "LJLLLLLL"}, new Class[]{List.class},
                    new ViewModelListRecordHook(), "familiar-adapter");
            XposedBridge.log(TAG + ": familiar-adapter record hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": familiar-adapter record hook failed: " + t);
        }

        // 24h) 好友/互关页观察者直达采集
        // 40.8.0: X.10bM/N/O(页面) 与 X.10aV/W/X(行) 的 onChanged；
        // 41.3.0: 改为 RawFamiliarFriendsFragment / MateUserActiveManager 的 lifecycle Observer。
        try {
            Class<?> obj = Object.class;
            XC_MethodHook rec = new ObserverChangedHook();
            String[][] targets = {
                    {"com.ss.android.ugc.aweme.friends.ui.RawFamiliarFriendsFragment$sam$androidx_lifecycle_Observer$0", "onChanged"},
                    {"com.ss.android.ugc.aweme.mate.matelist.data.MateUserActiveManager$sam$androidx_lifecycle_Observer$0", "onChanged"},
                    {"X.10bM", "onChanged"}, {"X.10bN", "onChanged"}, {"X.10bO", "onChanged"},
                    {"X.10aV", "onChanged"}, {"X.10aW", "onChanged"}, {"X.10aX", "onChanged"},
            };
            int ok = 0;
            for (String[] t : targets) {
                try {
                    XposedHelpers.findAndHookMethod(t[0], cl, t[1], obj, rec);
                    ok++;
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG + ": observer-record hooks installed " + ok + "/" + targets.length);
            DexClassMap.recordHook("static:observer", "观察者采集(静态)",
                    "X.10bM/N/O,10aV/W/X,10G2", "sam observers", ok > 0,
                    "onChanged x" + ok + "/" + targets.length);
        } catch (Throwable t) {
            DexClassMap.recordHook("static:observer", "观察者采集(静态)",
                    "X.10bM/N/O,10aV/W/X,10G2", "sam observers", false, t.getClass().getSimpleName());
            XposedBridge.log(TAG + ": observer-record hooks failed: " + t);
        }

        // 24g) 订阅代理采集：包装 NextLiveData 的 Observer 与 friendlist VM 的 Function1
        try {
            WrapCallbackHook w1 = new WrapCallbackHook(1);
            WrapCallbackHook w0 = new WrapCallbackHook(0);
            try {
                Class<?> nld = XposedHelpers.findClass(
                        "com.ss.android.ugc.aweme.arch.widgets.base.NextLiveData", cl);
                for (String m : new String[]{"observe", "LIZ"}) {
                    try {
                        XposedHelpers.findAndHookMethod(nld, m,
                                Class.forName("androidx.lifecycle.LifecycleOwner", false, cl),
                                Class.forName("androidx.lifecycle.Observer", false, cl), w1);
                    } catch (Throwable ignored) {
                    }
                }
                for (String m : new String[]{"observeForever", "LIZIZ"}) {
                    try {
                        XposedHelpers.findAndHookMethod(nld, m,
                                Class.forName("androidx.lifecycle.Observer", false, cl), w0);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> vm = XposedHelpers.findClass(
                        "com.ss.android.ugc.aweme.friends.friendlist.RawFriendListViewModel", cl);
                for (String m : new String[]{"CA", "EA"}) {
                    try {
                        XposedHelpers.findAndHookMethod(vm, m,
                                Class.forName("kotlin.jvm.functions.Function1", false, cl), w0);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": callback-wrap hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": callback-wrap hooks failed: " + t);
        }

        // 24f) LiveData 全局采集：setValue/postValue 携带的 User / List<User>
        //      注意 NextLiveData 重写了这两个方法，基类 hook 拦不到，需分别挂
        try {
            XC_MethodHook rec = new LiveDataRecordHook();
            try {
                Class<?> ld = XposedHelpers.findClass("androidx.lifecycle.LiveData", cl);
                try {
                    XposedHelpers.findAndHookMethod(ld, "setValue", Object.class, rec);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(ld, "postValue", Object.class, rec);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> nld = XposedHelpers.findClass(
                        "com.ss.android.ugc.aweme.arch.widgets.base.NextLiveData", cl);
                XC_MethodHook nrec = new LiveDataRecordHook("next");
                try {
                    XposedHelpers.findAndHookMethod(nld, "setValue", Object.class, nrec);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.findAndHookMethod(nld, "postValue", Object.class, nrec);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": livedata record hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": livedata record hook failed: " + t);
        }

        // 24d) 互关/关注分组适配器：getItemCount 时读取其内部完整列表
        try {
            Class<?> fla = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.following.ui.adapter.FollowerListGroupAdapter", cl);
            XposedHelpers.findAndHookMethod(fla, "getItemCount", new AdapterListRecordHook());
            XposedBridge.log(TAG + ": follower-adapter record hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": follower-adapter record hook failed: " + t);
        }

        // 24c) EzList 数据层：首次绑定时遍历全部条目采集（互关等列表）
        try {
            Class<?> ez = XposedHelpers.findClass(
                    "com.bytedance.ies.ezlist.adapter.EzListAdapter", cl);
            XposedHelpers.findAndHookMethod(ez, "getItem", int.class, new EzRecordHook());
            XposedBridge.log(TAG + ": ezlist-record hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ezlist-record hook failed: " + t);
        }

        // 24b) 互关页数据层：40.8.0 G3(item,int,List)；41.3.0 B3(06Eu,int,List)
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.mate.matelist.list.ui.holder.MateListItemHolder", cl);
            int n = hookByArity(holder, new String[]{"B3", "G3"}, 3,
                    new RecordListHook(0, 2), "mate-record");
            hookByArity(holder, new String[]{"onBind"}, 3,
                    new RecordListHook(0, 2), "mate-record-onbind");
            XposedBridge.log(TAG + ": mate-record hook installed " + n);
            DexClassMap.recordHook("static:07WO", "互关记录项(静态)",
                    "X.07WO", "B3/G3", n > 0, "B3/G3(item,int,List) x" + n);
        } catch (Throwable t) {
            DexClassMap.recordHook("static:07WO", "互关记录项(静态)",
                    "X.07WO", "B3/G3", false, t.getClass().getSimpleName());
            XposedBridge.log(TAG + ": mate-record hook failed: " + t);
        }

        // 14) 互关列表行（friends.friendlist.RawFriendListItemViewHolder）
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.friends.friendlist.RawFriendListItemViewHolder", cl);
            Class<?> userCls = XposedHelpers.findClass(T_USER, cl);
            int ok = 0;
            try {
                XposedHelpers.findAndHookMethod(holder, "L3",
                        userCls, String.class, new HideCellHook("relation", "friend-row"));
                ok++;
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(holder, "E3",
                        userCls, String.class, new HideCellHook("relation", "friend-row"));
                ok++;
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(holder, "I3",
                        userCls, new HideCellHook("relation", "friend-row"));
                ok++;
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": friend-row hooks installed " + ok + "/3");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": friend-row hooks failed: " + t);
        }

        // 15) 消息页顶部"在线好友"头像条（message2.onlinelist_refactor.DiffListAdapter）
        // v6.9：改为「整栏布局隐藏」——不再逐个隐藏头像，而是拿到该 RecyclerView 所在的
        // 那一栏容器整体 GONE。受独立开关 topbar 控制。onAttachedToRecyclerView 是拿到 rv 的
        // 最可靠入口；rv.post 延迟到布局完成后定位栏容器。
        try {
            Class<?> ad = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.message2.onlinelist_refactor.DiffListAdapter", cl);
            Class<?> rvCls = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView", cl);
            try {
                XposedHelpers.findAndHookMethod(ad, "onAttachedToRecyclerView", rvCls,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object rvObj = param.args.length > 0 ? param.args[0] : null;
                                    if (!(rvObj instanceof android.view.View)) return;
                                    ((android.view.View) rvObj).post(new TopBarApplyTask((android.view.View) rvObj));
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                XposedBridge.log(TAG + ": topbar attach hook installed");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": topbar attach hook failed: " + t);
            }
            XposedBridge.log(TAG + ": online-list hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": online-list hook failed: " + t);
        }

        // 24) 一起看邀请面板：真实绑定入口 D3(int,item,prev)，按 uid 精准折叠（好友池方案）
        //     链路：CallingInviteFriendAdapterV2.onBindBasicViewHolder → getItem(i)=IMContact/IMMember
        //           → CallingInviteFriendViewHolderV2.D3(i, item, prev)。在 D3 之前折叠，零闪烁。
        try {
            Class<?> vh5 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.group.viewholder.CallingInviteFriendViewHolderV2", cl);
            XposedHelpers.findAndHookMethod(vh5, "D3",
                    int.class, Object.class, Object.class, new InviteD3HideHook());
            XposedBridge.log(TAG + ": invite D3 uid-hide hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": invite D3 uid-hide hook failed: " + t);
        }
        // 24a) 「邀请一起看」联系人列表：IMShareContactsListAdapter.onBindViewHolder 按 uid 精准折叠
        //      （一起看面板的真实适配器，取代早先误判的 CallingInviteFriendAdapterV2）
        try {
            Class<?> ad = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.share.sharelist.IMShareContactsListAdapter", cl);
            Class<?> rvVh = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder", cl);
            Class<?> payload = XposedHelpers.findClass("java.util.List", cl);
            InviteBindHook ibh = new InviteBindHook();
            try {
                XposedHelpers.findAndHookMethod(ad, "onBindViewHolder", rvVh, int.class, ibh);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(ad, "onBindViewHolder",
                        rvVh, int.class, payload, ibh);
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": invite-sharelist uid-hide hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": invite-sharelist uid-hide hook failed: " + t);
        }

        // 24b) 兜底：其他选择面板用基类 E3(IMContact,String)（保留原逻辑，按 uid 判断）
        try {
            Class<?> vh5b = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.relations.viewholder.BaseSelectViewHolder", cl);
            Class<?> c5b = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.service.model.IMContact", cl);
            XposedHelpers.findAndHookMethod(vh5b, "E3",
                    c5b, String.class, new HideCellHook("share", "select-cell"));
            XposedBridge.log(TAG + ": select-vh hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": select-vh hook failed: " + t);
        }

        // 23) 分享面板 ViewModel 数据源（SharePanelViewModel）：rx/v8/LIZLLL/LJ
        try {
            Class<?> vm5 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.share.panel.viewmodel.SharePanelViewModel", cl);
            int ok5 = 0;
            for (String m : new String[]{"rx", "v8"}) {
                try {
                    XposedHelpers.findAndHookMethod(vm5, m,
                            List.class, boolean.class, new GenericListHook("shvm-" + m));
                    ok5++;
                } catch (Throwable ignored) {
                }
            }
            try {
                XposedHelpers.findAndHookMethod(vm5, "LIZLLL", List.class,
                        new GenericListHook("shvm-LIZLLL"));
                ok5++;
            } catch (Throwable ignored) {
            }
            try {
                // v6.7：改为非 mutate——LJ 是上游持久数据源，mutate 原地删除会污染，
                // 关闭 share 开关后该 list 不再刷新时隐藏项无法恢复（头像不显示的根因）。
                // 非 mutate 只替换返回值，不触碰原始 list，天然可逆。
                XposedHelpers.findAndHookMethod(vm5, "LJ", new ReturnListFilter("shvm-LJ"));
                ok5++;
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": share-vm hooks installed " + ok5 + "/4");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-vm hooks failed: " + t);
        }

        // v6.9：旧的文本清扫 hook（22 setText / 21 rv-layout / 20 dialog-scrub /
        // 19 invite-scrub）已全部移除。所有入口统一走 uid 精准识别，不再按昵称/文本隐藏。
        // 18) 分享列表勾选行（IMShareContactViewHolder）：E3(IMContact) / N3(IMContact,Z,Z)
        try {
            Class<?> vh4 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.share.sharelist.viewholder.IMShareContactViewHolder", cl);
            Class<?> c4 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.service.model.IMContact", cl);
            int ok4 = 0;
            try {
                XposedHelpers.findAndHookMethod(vh4, "E3", c4, new HideByArgHook(0));
                ok4++;
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(vh4, "N3",
                        c4, boolean.class, boolean.class, new HideByArgHook(0));
                ok4++;
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(vh4, "Q3", c4, new HideByArgHook(0));
                ok4++;
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": share-contact-vh hooks installed " + ok4 + "/3");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-contact-vh hooks failed: " + t);
        }

        // 17) 分享列表单元格通用绑定（一起看邀请面板等）：IMShareBaseViewHolder.D3(int,String,String,Z,IMContact)
        try {
            Class<?> vh3 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.share.sharelist.viewholder.IMShareBaseViewHolder", cl);
            Class<?> c3 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.service.model.IMContact", cl);
            XposedHelpers.findAndHookMethod(vh3, "D3",
                    int.class, String.class, String.class, boolean.class, c3,
                    new HideByArgHook(4));
            XposedBridge.log(TAG + ": share-vh hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-vh hook failed: " + t);
        }

        // 16) 一起看/分享面板「最近分享」行（share.panel.viewholder.SharePanelRecentShareViewHolder）
        try {
            Class<?> vh2 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.share.panel.viewholder.SharePanelRecentShareViewHolder", cl);
            Class<?> c2 = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.service.model.IMContact", cl);
            XposedHelpers.findAndHookMethod(vh2, "D3", c2, new HideCellHook("share", "recent-share"));
            XposedBridge.log(TAG + ": recent-share hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": recent-share hook failed: " + t);
        }

        // 8) 分享面板（kiwi 框架）：联系人单元格渲染时折叠被隐藏联系人
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.sdk.share.panel.IMBaseViewHolder", cl);
            Class<?> contact = XposedHelpers.findClass(
                    "com.ss.android.ugc.aweme.im.service.model.IMContact", cl);
            XposedHelpers.findAndHookMethod(holder, "E3", contact, String.class,
                    new HideCellHook("share", "share-cell"));
            XposedBridge.log(TAG + ": share-cell hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": share-cell hook failed: " + t);
        }
    }

    /** 对返回 List 的方法做返回值过滤（如 ViewModel.getData()）。 */
    private static class ReturnListFilter extends XC_MethodHook {
        private final String label;
        private final boolean mutate;

        ReturnListFilter(String label) {
            this(label, false);
        }

        ReturnListFilter(String label, boolean mutate) {
            this.label = label;
            this.mutate = mutate;
        }

        @SuppressWarnings("unchecked")
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object r = param.getResult();
                if (!(r instanceof List)) return;
                List<Object> list = (List<Object>) r;
                if (list.isEmpty()) return;
                String fk = featureOf(label);
                boolean shouldFilter = enabled && !uids.isEmpty()
                        && (fk == null || isFeatureOn(fk));
                if (!shouldFilter) {
                    // 总开关/功能关闭（或名单清空）：恢复显示。
                    // v6.7：list 引用可能因页面重建而变化，先按当前引用恢复，
                    // 再清空全部残留备份，确保没有任何历史删除态遗留。
                    restoreMutated(list);
                    clearAllMutatedBackups();
                    return;
                }
                ArrayList<Object> keep = new ArrayList<>(list.size());
                ArrayList<Object> drop = new ArrayList<>();
                for (Object item : list) {
                    if (isHidden(item)) {
                        drop.add(item);
                    } else {
                        keep.add(item);
                    }
                }
                if (mutate) {
                    if (drop.isEmpty()) {
                        // 已过滤干净：保持现状，绝不在此恢复备份
                        // （否则开启状态下把隐藏项还原回来 → 闪现/反复）。
                        return;
                    }
                    // 原地删除前先按引用备份原始内容，供开关关闭时还原
                    if (!MUTATED_BACKUP.containsKey(list)) {
                        MUTATED_BACKUP.put(list, new ArrayList<>(list));
                    }
                    try {
                        list.removeAll(drop);
                    } catch (Throwable ignored) {
                        param.setResult(keep);
                    }
                } else {
                    if (drop.isEmpty()) return;
                    param.setResult(keep);
                }
                XposedBridge.log(TAG + ": filtered " + drop.size() + " item(s) [" + label
                        + (mutate ? "-mut" : "")
                        + " by " + (param.thisObject == null ? "?"
                        : param.thisObject.getClass().getName()) + "]");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": return filter error [" + label + "]: " + t);
            }
        }

        /** 还原被 mutate 前的原始列表内容（保持同一个 List 引用）。 */
        @SuppressWarnings("unchecked")
        private void restoreMutated(List<Object> list) {
            java.util.List backup = MUTATED_BACKUP.remove(list);
            if (backup == null || backup.isEmpty()) return;
            try {
                list.clear();
                list.addAll(backup);
                XposedBridge.log(TAG + ": restored " + backup.size() + " item(s) [" + label + "-mut]");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": restore error [" + label + "]: " + t);
            }
        }
    }

    /**
     * v7.0 访客/互动消息列表过滤：argIdx>=0 在 before 过滤入参 List，argIdx<0 在 after 过滤返回 List。
     * 非 mutate，直接 setResult / 替换 args，天然可逆（开关关闭时 hook 直接 return 不改动）。
     * 元素按 uid 判定（ProfileVisitorStruct 继承 User，resolveUid 能识别 getUid）。
     */
    private static class VisitorListFilter extends XC_MethodHook {
        private final String label;
        private final int argIdx;   // >=0 入参，<0 返回值

        VisitorListFilter(String label, int argIdx) {
            this.label = label;
            this.argIdx = argIdx;
        }

        private boolean on() {
            return enabled && uids != null && !uids.isEmpty();
        }

        /** 关键开关：访客归 visitorHidden，互动消息归 interactHidden。 */
        private boolean featureOn() {
            if (label.startsWith("visitor")) return visitorHidden;
            if (label.startsWith("interact")) return interactHidden;
            return true;
        }

        @SuppressWarnings("unchecked")
        private List<Object> filter(List<?> src) {
            if (src == null || src.isEmpty()) return null;
            ArrayList<Object> keep = new ArrayList<>(src.size());
            int hit = 0;
            for (Object item : src) {
                if (isHidden(item)) hit++;
                else keep.add(item);
            }
            if (hit == 0) return null;
            XposedBridge.log(TAG + ": filtered " + hit + " item(s) [" + label + "]");
            return keep;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (argIdx < 0) return;
            try {
                if (!on() || !featureOn() || param.args.length <= argIdx) return;
                Object a = param.args[argIdx];
                if (!(a instanceof List)) return;
                List<Object> keep = filter((List<?>) a);
                if (keep != null) param.args[argIdx] = keep;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": visitor arg filter error [" + label + "]: " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (argIdx >= 0) return;
            try {
                if (!on() || !featureOn()) return;
                Object r = param.getResult();
                if (!(r instanceof List)) return;
                List<Object> keep = filter((List<?>) r);
                if (keep != null) param.setResult(keep);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": visitor return filter error [" + label + "]: " + t);
            }
        }
    }

    /**
     * v6.7：功能/总开关关闭时，把历史上所有被 mutate 原地删除的列表全部还原。
     * 解决「list 引用随页面重建变化，按当前引用恢复失效 → 头像仍隐藏」的问题。
     * 遍历备份，对每个仍然存活的 list 引用写回原始内容，然后清空备份表。
     */
    @SuppressWarnings("unchecked")
    private static void clearAllMutatedBackups() {
        try {
            java.util.List<java.util.Map.Entry<List, java.util.List>> entries;
            synchronized (MUTATED_BACKUP) {
                entries = new java.util.ArrayList<>(MUTATED_BACKUP.entrySet());
                MUTATED_BACKUP.clear();
            }
            int restored = 0;
            for (java.util.Map.Entry<List, java.util.List> e : entries) {
                try {
                    List list = e.getKey();
                    java.util.List backup = e.getValue();
                    if (list == null || backup == null) continue;
                    list.clear();
                    list.addAll(backup);
                    restored++;
                } catch (Throwable ignored) {
                }
            }
            if (restored > 0) {
                XposedBridge.log(TAG + ": clearAllMutatedBackups restored " + restored + " list(s)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": clearAllMutatedBackups error: " + t);
        }
    }

    // v6.9：旧的视图文本清扫体系（Dialog/View scrub、hideRowOf、hasAvatarAndText 等）已删除。
    // 所有隐藏统一走 uid 精准识别；仅保留 uid 折叠行的复用恢复工具：

    private static void restoreAllCollapsedRows() {
        try {
            java.util.List<android.view.View> vs;
            synchronized (HIDDEN_ROWS) {
                vs = new java.util.ArrayList<android.view.View>(HIDDEN_ROWS.keySet());
            }
            for (android.view.View v : vs) restoreRow(v);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreRow(android.view.View v) {
        int[] orig;
        synchronized (HIDDEN_ROWS) {
            orig = HIDDEN_ROWS.remove(v);
        }
        try {
            v.setVisibility(android.view.View.VISIBLE);
            if (orig != null && v.getLayoutParams() != null) {
                v.getLayoutParams().width = orig[0];
                v.getLayoutParams().height = orig[1];
                v.setLayoutParams(v.getLayoutParams());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 延迟到布局完成后处理头像条（具名类，避免 D8 嵌套匿名类 bug）。 */
    private static class TopBarApplyTask implements Runnable {
        private final android.view.View rv;
        TopBarApplyTask(android.view.View rv) { this.rv = rv; }
        @Override
        public void run() { applyTopBarVisibility(rv); }
    }

    /**
     * v6.9 消息页顶部头像条「整栏隐藏」。
     * rv 就是在线好友那一栏的主体 RecyclerView；整栏隐藏 = 把 rv GONE 并高度归零，
     * 使其不再占位。topbar 开关关闭时恢复原样。
     * 同时输出父链诊断，便于必要时把隐藏目标上移到外层容器。
     */
    private static void applyTopBarVisibility(android.view.View rv) {
        if (rv == null) return;
        try {
            boolean hide = enabled && topbarHidden;
            synchronized (TOPBAR_BARS) {
                if (!hide) {
                    // 开关关闭：恢复所有被隐藏的头像条
                    for (android.view.View bar : new java.util.ArrayList<>(TOPBAR_BARS.keySet())) {
                        int[] orig = TOPBAR_BARS.remove(bar);
                        if (orig == null) continue;
                        bar.setVisibility(orig[0]);
                        android.view.ViewGroup.LayoutParams lp = bar.getLayoutParams();
                        if (lp != null) {
                            lp.width = orig[1];
                            lp.height = orig[2];
                            bar.setLayoutParams(lp);
                        }
                    }
                    return;
                }
                // 开关开启：整栏 GONE（记录原始可见性 + 宽高）
                if (!TOPBAR_BARS.containsKey(rv)) {
                    android.view.ViewGroup.LayoutParams lp = rv.getLayoutParams();
                    TOPBAR_BARS.put(rv, new int[]{
                            rv.getVisibility(),
                            lp == null ? -1 : lp.width,
                            lp == null ? -1 : lp.height});
                    // 诊断父链（仅首次）
                    StringBuilder chain = new StringBuilder();
                    android.view.View cur = rv;
                    for (int d = 0; d < 5 && cur != null; d++) {
                        android.view.ViewGroup.LayoutParams clp = cur.getLayoutParams();
                        chain.append(cur.getClass().getSimpleName())
                                .append('(').append(clp == null ? -1 : clp.height).append("px)");
                        android.view.ViewParent p = cur.getParent();
                        cur = p instanceof android.view.View ? (android.view.View) p : null;
                        if (cur != null) chain.append("<-");
                    }
                    XposedBridge.log(TAG + ": topbar hidden rv, chain=" + chain);
                }
                rv.setVisibility(android.view.View.GONE);
                android.view.ViewGroup.LayoutParams lp = rv.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    rv.setLayoutParams(lp);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyTopBarVisibility error: " + t);
        }
    }

    /** 适配器内部列表采集（反射字段 b） */
    private static class AdapterListRecordHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                synchronized (EZ_SEEN) {
                    if (!EZ_SEEN.add(param.thisObject)) return;
                }
                Object l;
                try {
                    l = XposedHelpers.getObjectField(param.thisObject, "b");
                } catch (Throwable t) {
                    return;
                }
                if (!(l instanceof java.util.List)) return;
                int before;
                synchronized (FRIENDS) {
                    before = FRIENDS.size();
                }
                for (Object o : (java.util.List<?>) l) recordFriend(o);
                int after;
                synchronized (FRIENDS) {
                    after = FRIENDS.size();
                }
                XposedBridge.log(TAG + ": adapter-list size=" + ((java.util.List<?>) l).size()
                        + " friends " + before + "->" + after);
            } catch (Throwable ignored) {
            }
        }
    }

    /** EzList：首次访问时遍历全部条目采集 */
    private static class EzRecordHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object item = param.getResult();
                recordFriend(item);
                synchronized (EZ_SEEN) {
                    if (!EZ_SEEN.add(param.thisObject)) return;
                }
                int n;
                try {
                    n = (Integer) XposedHelpers.callMethod(param.thisObject, "getDataSize");
                } catch (Throwable t) {
                    return;
                }
                int before;
                synchronized (FRIENDS) {
                    before = FRIENDS.size();
                }
                for (int i = 0; i < n && i < 3000; i++) {
                    try {
                        recordFriend(XposedHelpers.callMethod(param.thisObject, "getItem", i));
                    } catch (Throwable ignored) {
                    }
                }
                int after;
                synchronized (FRIENDS) {
                    after = FRIENDS.size();
                }
                XposedBridge.log(TAG + ": ezlist bulk size=" + n + " friends " + before
                        + "->" + after);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final java.util.Set<Object> EZ_SEEN =
            java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Object, Boolean>());

    /** 从绑定参数里的整份列表采集好友（数据层，不依赖可见行）。 */
    private static class RecordListHook extends XC_MethodHook {
        private final int itemIdx;
        private final int listIdx;

        RecordListHook(int itemIdx, int listIdx) {
            this.itemIdx = itemIdx;
            this.listIdx = listIdx;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                int before;
                synchronized (FRIENDS) {
                    before = FRIENDS.size();
                }
                if (itemIdx < param.args.length) recordFriend(param.args[itemIdx]);
                if (listIdx < param.args.length) {
                    Object l = param.args[listIdx];
                    if (l instanceof java.util.List) {
                        for (Object o : (java.util.List<?>) l) recordFriend(o);
                    }
                }
                int after;
                synchronized (FRIENDS) {
                    after = FRIENDS.size();
                }
                if (after > before) {
                    XposedBridge.log(TAG + ": friends now " + after);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** item 在指定参数位、holder 是 thisObject 时：命中则折叠该 cell。 */    private static class HideByArgHook extends XC_MethodHook {
        private final int itemIdx;

        HideByArgHook(int itemIdx) {
            this.itemIdx = itemIdx;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object item = param.args.length > itemIdx ? param.args[itemIdx] : null;
                collapseRowByUid(param.thisObject, item, "share-vh");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 一起看邀请面板：在 holder.D3(int,item,prev) 执行前按 uid 精准折叠。
     * 走 item 对象（IMContact/IMMember/IMUser）提取 uid，不用视图文本匹配——
     * 无闪烁、且每次绑定都先恢复，彻底解决 RecyclerView 复用误伤。
     * 依赖功能开关 "invite"。
     */
    private static class InviteD3HideHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object holder = param.thisObject;
                Object v = safeField(holder, "itemView");
                if (!(v instanceof android.view.View)) return;
                android.view.View view = (android.view.View) v;
                // 1) 每次绑定先无条件恢复：被复用的行不属于隐藏好友时必须重现
                if (HIDDEN_ROWS.containsKey(view)) restoreRow(view);

                if (!enabled || !isFeatureOn("invite") || uids.isEmpty()) return;
                Object item = param.args.length > 1 ? param.args[1] : null;
                if (item == null) return;
                recordFriend(item);
                // 用类型感知的 getter 提取 uid（J3 对 IMUser 返回首字母，不可用）
                String uid = inviteUid(item);
                if (uid == null || uid.isEmpty()) return;
                if (!uids.contains(uid)) return;   // 用真实好友 uid 精准判断
                // 2) 命中：折叠整行
                synchronized (HIDDEN_ROWS) {
                    HIDDEN_ROWS.put(view, new int[]{
                            view.getLayoutParams() == null ? -1 : view.getLayoutParams().width,
                            view.getLayoutParams() == null ? -1 : view.getLayoutParams().height});
                }
                view.setVisibility(android.view.View.GONE);
                if (view.getLayoutParams() != null) {
                    view.getLayoutParams().width = 0;
                    view.getLayoutParams().height = 0;
                    view.setLayoutParams(view.getLayoutParams());
                }
                XposedBridge.log(TAG + ": invite row hidden [uid]=" + uid);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 邀请一起看联系人列表：onBindViewHolder 之后按 uid 折叠。
     * 从 adapter.getData().get(position) 拿真实 IMContact，避免视图文本匹配。
     * 每次绑定先恢复，解决复用误伤。
     */
    private static class InviteBindHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                // v6.8 修复：此 adapter(IMShareContactsListAdapter) 同时服务「分享面板」与
                // 「一起看邀请面板」。数据层 share-list-getData 用 share 开关，cell 层也必须用
                // share 开关，否则关 share 后数据恢复但头像仍被 invite 开关折叠（头像条恢复、
                // 头像不恢复的根因）。邀请面板的隐藏由独立的 CallingInviteFriendAdapterV2.D3
                // （invite 开关）负责，这里改 share 不影响邀请面板。
                if (!enabled || !isFeatureOn("share") || uids.isEmpty()) return;
                Object holder = param.args.length > 0 ? param.args[0] : null;
                int pos = param.args.length > 1 ? (Integer) param.args[1] : -1;
                if (holder == null || pos < 0) return;
                Object v = safeField(holder, "itemView");
                if (!(v instanceof android.view.View)) return;
                android.view.View view = (android.view.View) v;
                // 先恢复（复用），再判断
                if (HIDDEN_ROWS.containsKey(view)) restoreRow(view);

                Object item = null;
                try {
                    Object data = XposedHelpers.callMethod(param.thisObject, "getData");
                    if (data instanceof java.util.List) {
                        java.util.List<?> l = (java.util.List<?>) data;
                        if (pos < l.size()) item = l.get(pos);
                    }
                } catch (Throwable ignored) {
                }
                if (item == null) return;
                recordFriend(item);
                String uid = inviteUid(item);
                if (uid == null || uid.isEmpty() || !uids.contains(uid)) return;

                synchronized (HIDDEN_ROWS) {
                    HIDDEN_ROWS.put(view, new int[]{
                            view.getLayoutParams() == null ? -1 : view.getLayoutParams().width,
                            view.getLayoutParams() == null ? -1 : view.getLayoutParams().height});
                }
                view.setVisibility(android.view.View.GONE);
                if (view.getLayoutParams() != null) {
                    view.getLayoutParams().width = 0;
                    view.getLayoutParams().height = 0;
                    view.setLayoutParams(view.getLayoutParams());
                }
                XposedBridge.log(TAG + ": invite row hidden [uid]=" + uid);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 邀请面板官方 uid 提取：IMMember.LJIJJLI() / IMUser.LJJJLZIJ()，再回退通用 getter。 */
    private static String inviteUid(Object contact) {
        if (contact == null) return null;
        // 按类型选正确的 uid getter：IMUser.LJJJLZIJ() 返回的是首字母，不是 uid！
        String cn = contact.getClass().getName();
        String[] getters;
        if (cn.contains("IMMember")) {
            getters = new String[]{"LJIJJLI", "getUidS", "getUid", "getSingleId"};
        } else {
            getters = new String[]{"getUidS", "getUid", "getSingleId", "getSecUid"};
        }
        try {
            String s = tryStr(contact, getters);
            if (s != null && !s.isEmpty() && s.length() >= 5) return s;
        } catch (Throwable ignored) {
        }
        // 兜底：contact 内可能包着 User
        Object u = matchAllowed(contact, contact.getClass(), 0);
        if (u != null && u != contact) {
            return tryStr(u, new String[]{"getUidS", "getUid", "getId"});
        }
        return null;
    }

    private static Object safeField(Object o, String name) {
        if (o == null) return null;
        try {
            return XposedHelpers.getObjectField(o, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v6 行级折叠：与一起看面板同一套机制——每次绑定先恢复被复用的行，
     * 再用真实 uid 判断；命中才折叠。杜绝文本误伤与滚动闪烁。
     */
    private static void collapseRowByUid(Object holder, Object item, String label) {
        collapseRowByUid(holder, item, label, featureOf(label));
    }

    /** featureKey 显式指定该行所属功能开关，null 表示不受开关控制（仅总开关）。 */
    private static void collapseRowByUid(Object holder, Object item, String label, String featureKey) {
        if (holder == null || item == null) return;
        Object f = safeField(holder, "itemView");
        android.view.View v = f instanceof android.view.View ? (android.view.View) f : null;
        if (v == null) {
            f = safeField(holder, "d");
            v = f instanceof android.view.View ? (android.view.View) f : null;
        }
        if (v == null) return;
        // 1) 复用恢复：同一行再次绑定到正常联系人时必须重现
        if (HIDDEN_ROWS.containsKey(v)) restoreRow(v);
        if (!enabled || uids.isEmpty()) return;
        // 开关关闭时不再折叠（行已在上一步 restore，恢复显示）
        if (featureKey != null && !isFeatureOn(featureKey)) return;
        // 2) uid 精准判定（不读昵称、不扫文本）
        String uid = resolveUid(item);
        if (uid == null || !uids.contains(uid)) return;
        synchronized (HIDDEN_ROWS) {
            HIDDEN_ROWS.put(v, new int[]{
                    v.getLayoutParams() == null ? -1 : v.getLayoutParams().width,
                    v.getLayoutParams() == null ? -1 : v.getLayoutParams().height});
        }
        v.setVisibility(android.view.View.GONE);
        if (v.getLayoutParams() != null) {
            v.getLayoutParams().width = 0;
            v.getLayoutParams().height = 0;
            v.setLayoutParams(v.getLayoutParams());
        }
        XposedBridge.log(TAG + ": row hidden [" + label + " uid]=" + uid);
    }

    /** 单元格命中名单则折叠。featureKey 指定所属开关；label 仅用于日志。 */
    private static class HideCellHook extends XC_MethodHook {
        private final String featureKey;
        private final String label;
        HideCellHook() { this(null, "cell"); }
        HideCellHook(String featureKey, String label) {
            this.featureKey = featureKey;
            this.label = label;
        }
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object contact = param.args.length > 0 ? param.args[0] : null;
                recordFriend(contact);
                collapseRowByUid(param.thisObject, contact, label, featureKey);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 拿到 Application 上下文后：查 Provider + 注册广播接收器。 */
    private static void installContextHook(ClassLoader cl) {
        try {
            Class<?> appCls = XposedHelpers.findClass(Application.class.getName(), cl);
            XposedHelpers.findAndHookMethod(appCls, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                initTransport(ctx);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": transport init failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": context hook failed: " + t);
        }
    }

    private static void initTransport(Context ctx) {
        APP_CTX = ctx;
        boolean first = !transportInited;
        transportInited = true;
        // v8.0 修复：移除旧版「Provider 查询后覆盖配置」逻辑。
        // 配置源已迁移到抖音私有目录 dyhide/xpro_hide.txt（handleLoadPackage 时 loadPersistedConfig），
        // 新包 provider 无数据，返回空 entries 会把正确配置覆盖成只剩 enabled 一行（重启后失效、联系人归零的根因）。

        // 每个 Application 实例都注册接收器（去重），避免实例回收后广播失效
        synchronized (REGISTERED_CTX) {
            if (REGISTERED_CTX.contains(ctx)) return;
            REGISTERED_CTX.add(ctx);
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_UPDATE);
            filter.addAction(ACTION_REQ_FRIENDS);
            filter.addAction(ACTION_OPEN_PANEL);
            filter.addAction(ACTION_TOGGLE_TEMP);
            filter.addAction(ACTION_RECHECK);
            BroadcastReceiver receiver = new UpdateReceiver();
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, 2 /* Context.RECEIVER_EXPORTED */);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            XposedBridge.log(TAG + ": update receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": receiver register failed: " + t);
        }
    }

    private static class UpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (ACTION_RECHECK.equals(action)) {
                    // 主进程才有 MainActivity 引用，这里才能弹检测提示
                    boolean hasAct = LAST_ACTIVITY.get() != null;
                    XposedBridge.log(TAG + ": recheck broadcast received, hasActivity=" + hasAct);
                    if (hasAct) {
                        try { CompatCheck.rerun(); }
                        catch (Throwable t) { XposedBridge.log(TAG + ": recheck rerun failed " + t); }
                    } else {
                        // 主进程也没 Activity：直接清 stamp，下次进入自动检测
                        try {
                            File f = new File("/data/user/0/" + TARGET_PKG
                                    + "/dyhide/compat_stamp.txt");
                            if (f.exists()) f.delete();
                        } catch (Throwable ignored) {
                        }
                    }
                    return;
                }
                if (ACTION_TOGGLE_TEMP.equals(action)) {
                    toggleTempPause(context);
                    return;
                }
                if (ACTION_OPEN_PANEL.equals(action)) {
                    android.app.Activity a = LAST_ACTIVITY.get();
                    Context pc = a != null ? a : APP_CTX;
                    XposedBridge.log(TAG + ": open-panel broadcast ctx="
                            + (pc == null ? "null" : pc.getClass().getName()));
                    try {
                        InAppPanel.show(pc);
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + ": panel open FAILED: "
                                + android.util.Log.getStackTraceString(e));
                    }
                    return;
                }
                if (ACTION_REQ_FRIENDS.equals(action)) {
                    replyFriends(context);
                    return;
                }
                String entries = intent.getStringExtra("entries");
                boolean en = intent.getBooleanExtra("enabled", true);
                apply(entries == null ? "" : entries, en);
                persistConfig(context, entries == null ? "" : entries, en);
                XposedBridge.log(TAG + ": broadcast applied uids=" + uids.size()
                        + " names=" + names.size() + " enabled=" + enabled);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": broadcast error: " + t);
            }
        }
    }

    /** 主动推送好友列表给 App（节流 1.5 秒，节流期间安排延迟补发，避免丢数据）。 */
    private static void publishFriends() {
        long now = System.currentTimeMillis();
        boolean throttled;
        synchronized (FRIENDS) {
            throttled = now - lastPublish < 1500;
            if (!throttled) lastPublish = now;
        }
        if (throttled) {
            if (PENDING_PUBLISH.compareAndSet(false, true)) {
                Thread t = new Thread(new PublishTrailing());
                t.setDaemon(true);
                t.start();
            }
            return;
        }
        doPublishFriends();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PENDING_PUBLISH =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static class PublishTrailing implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(1600);
            } catch (Throwable ignored) {
            }
            PENDING_PUBLISH.set(false);
            publishFriends();
        }
    }

    /** 强制立即发布（绕过节流），用于批量采集完成后。 */
    static void forcePublishFriends() {
        synchronized (FRIENDS) {
            lastPublish = 0L;
        }
        publishFriends();
    }

    private static void doPublishFriends() {
        Context ctx = APP_CTX;
        if (ctx == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (FRIENDS) {
                for (java.util.Map.Entry<String, String> e : FRIENDS.entrySet()) {
                    sb.append(e.getKey()).append(',').append(e.getValue()).append('\n');
                }
            }
            Intent out = new Intent(ACTION_FRIENDS);
            out.setPackage(MY_PKG);
            out.addFlags(0x01000000 /* FLAG_RECEIVER_INCLUDE_BACKGROUND */);
            out.putExtra("data", sb.toString());
            ctx.sendBroadcast(out);
            // 持久化好友表，重启后仍可选取
            try {
                FileOutputStream fo = new FileOutputStream(
                        new File(dataDir(ctx), FRIENDS_FILE));
                fo.write(sb.toString().getBytes("UTF-8"));
                fo.close();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 回传采集到的好友列表给模块 App。 */
    private static void replyFriends(Context context) {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (FRIENDS) {
                for (java.util.Map.Entry<String, String> e : FRIENDS.entrySet()) {
                    sb.append(e.getKey()).append(',').append(e.getValue()).append('\n');
                }
            }
            Intent out = new Intent(ACTION_FRIENDS);
            out.setPackage(MY_PKG);
            out.putExtra("data", sb.toString());
            context.sendBroadcast(out);
            XposedBridge.log(TAG + ": replied friends=" + FRIENDS.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": replyFriends failed: " + t);
        }
    }

    /** 关系全量同步投递点：扫描 Response 的全部 List 出口采集好友 */
    private static class RelationFetchHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length < 2 || param.args[1] == null) return;
                Object resp = param.args[1];
                int before;
                synchronized (FRIENDS) {
                    before = FRIENDS.size();
                }
                StringBuilder dbg = new StringBuilder();
                for (java.lang.reflect.Method m : resp.getClass().getDeclaredMethods()) {
                    try {
                        if (m.getParameterCount() != 0) continue;
                        if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                        m.setAccessible(true);
                        Object l = m.invoke(resp);
                        if (!(l instanceof List)) continue;
                        List<?> ll = (List<?>) l;
                        if (!ll.isEmpty()) {
                            dbg.append(' ').append(m.getName()).append('=').append(ll.size())
                                    .append('/').append(ll.get(0).getClass().getSimpleName());
                        }
                        for (Object o : ll) recordDeep(o, 0);
                    } catch (Throwable ignored) {
                    }
                }
                int after;
                synchronized (FRIENDS) {
                    after = FRIENDS.size();
                }
                XposedBridge.log(TAG + ": rel fired " + dbg + " friends "
                        + before + "->" + after);
                if (after > before) {
                    forcePublishFriends();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 观察者 onChanged 直达采集 */
    private static class ObserverChangedHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length > 0) recordDeep(param.args[0], 0);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 回调代理：转发原调用，同时把携带值交给采集 */
    private static class CallbackProxy implements java.lang.reflect.InvocationHandler {
        private final Object target;

        CallbackProxy(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) throws Throwable {
            if (a != null && a.length > 0) {
                try {
                    recordDeep(a[0], 0);
                } catch (Throwable ignored) {
                }
            }
            return m.invoke(target, a);
        }
    }

    /** 深度受限采集：List 递归展开，包装类交给 recordFriend 深扫 */
    private static void recordDeep(Object v, int depth) {
        if (v == null || depth > 2) return;
        if (v instanceof List) {
            for (Object o : (List<?>) v) recordDeep(o, depth + 1);
            return;
        }
        String cn = v.getClass().getName();
        if (cn.contains("User") || cn.equals(T_IMUSER) || cn.startsWith("X.")
                || cn.contains("Pair")) {
            recordFriend(v);
        }
    }

    /** hook 订阅方法，把 Observer/Function1 参数换成代理 */
    private static class WrapCallbackHook extends XC_MethodHook {
        private final int idx;

        WrapCallbackHook(int idx) {
            this.idx = idx;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length <= idx) return;
                Object cb = param.args[idx];
                if (cb == null || java.lang.reflect.Proxy.isProxyClass(cb.getClass())) return;
                Class<?> iface = null;
                for (Class<?> i : cb.getClass().getInterfaces()) {
                    for (java.lang.reflect.Method m : i.getMethods()) {
                        String n = m.getName();
                        if (n.equals("onChanged") || n.equals("apply")) {
                            iface = i;
                            break;
                        }
                    }
                    if (iface != null) break;
                }
                if (iface == null) return;
                Object px = java.lang.reflect.Proxy.newProxyInstance(
                        cb.getClass().getClassLoader(),
                        new Class<?>[]{iface}, new CallbackProxy(cb));
                param.args[idx] = px;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void cheapRecord(Object v) {
        try {
            if (v == null) return;
            if (v instanceof List) {
                List<?> l = (List<?>) v;
                if (l.isEmpty()) return;
                Object f = l.get(0);
                if (f == null) return;
                if (!f.getClass().getName().contains("User")) return;
                for (Object o : l) recordFriend(o);
                return;
            }
            String cn = v.getClass().getName();
            if (cn.contains("profile.model.User") || cn.equals(T_IMUSER)) recordFriend(v);
        } catch (Throwable ignored) {
        }
    }



    /** ViewModel 的 UA/ZA(List)：整份好友列表入口 */
    private static class ViewModelListRecordHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object v = param.args.length > 0 ? param.args[0] : null;
                if (v == null) return;
                long now = System.currentTimeMillis();
                int size = v instanceof List ? ((List<?>) v).size() : 0;
                synchronized (DEBUG_THROTTLE) {
                    Long last = DEBUG_THROTTLE.get("vmlist");
                    if (last == null || now - last > 5000) {
                        DEBUG_THROTTLE.put("vmlist", now);
                        XposedBridge.log(TAG + ": vmlist "
                                + v.getClass().getName() + " size=" + size);
                    }
                }
                recordDeep(v, 0);
                forcePublishFriends();
            } catch (Throwable ignored) {
            }
        }
    }

    /** LiveData setValue/postValue：捕获所有流经的数据 */
    private static class LiveDataRecordHook extends XC_MethodHook {
        private final String tag;

        LiveDataRecordHook() {
            this.tag = "base";
        }

        LiveDataRecordHook(String tag) {
            this.tag = tag;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args.length > 0) {
                    cheapRecord(param.args[0]);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void recordFriend(Object obj) {
        try {
            if (obj == null) return;
            Object o = matchAllowed(obj, obj.getClass(), 0);
            if (o == null) return;
            if (T_IMUSER.equals(o.getClass().getName())) {
                Object u = convertImUser(o);
                if (u == null) return;
                o = u;
            }
            String uid = tryStr(o, new String[]{"getUid", "getUidS", "getId"});
            String name = tryStr(o, new String[]{"getNickname", "getDisplayName", "getTitle"});
            if (uid == null || uid.isEmpty() || name == null || name.isEmpty()) return;
            if (uid.length() < 5) return;
            boolean added;
            synchronized (FRIENDS) {
                added = !FRIENDS.containsKey(uid);
                FRIENDS.put(uid, name);
            }
            if (added) publishFriends();
        } catch (Throwable ignored) {
        }
    }

    private static boolean persistConfig(Context ctx, String entries, boolean en) {
        FileOutputStream out = null;
        try {
            byte[] body = (("enabled=" + en + "\n")
                    + (entries == null ? "" : entries)).getBytes("UTF-8");
            File dir = dataDir(ctx);
            File tmp = new File(dir, CFG_FILE + ".tmp");
            File dst = new File(dir, CFG_FILE);
            // 先写临时文件再原子替换：避免半写；新文件属主为当前进程，
            // 可自愈被 root 手工改坏属主导致 EACCES 的情况。
            out = new FileOutputStream(tmp, false);
            out.write(body);
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(dst)) {
                FileOutputStream direct = new FileOutputStream(dst, false);
                direct.write(body);
                direct.close();
                XposedBridge.log(TAG + ": persist via direct fallback");
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": persist failed: " + t + " @ " + callerTag());
            return false;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void loadPersistedFriends() {
        try {
            File f = new File("/data/user/0/" + TARGET_PKG + "/" + DATA_DIR_NAME + "/" + FRIENDS_FILE);
            FileInputStream in = new FileInputStream(f);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            synchronized (FRIENDS) {
                for (String line : sb.toString().split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    String[] parts = t.split(",", 2);
                    if (parts.length < 2) continue;
                    if (!parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                        FRIENDS.put(parts[0].trim(), parts[1].trim());
                    }
                }
            }
            XposedBridge.log(TAG + ": friends loaded " + FRIENDS.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": no friends cache");
        }
    }

    private static void loadPersistedConfig() {
        try {
            File f = new File("/data/user/0/" + TARGET_PKG + "/" + DATA_DIR_NAME + "/" + CFG_FILE);
            FileInputStream in = new FileInputStream(f);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            String raw = sb.toString();
            boolean en = true;
            String entries;
            int idx = raw.indexOf('\n');
            if (raw.startsWith("enabled=") && idx >= 0) {
                en = Boolean.parseBoolean(raw.substring(8, idx));
                entries = raw.substring(idx + 1);
            } else {
                entries = raw;
            }
            apply(entries, en);
            XposedBridge.log(TAG + ": persisted config loaded uids=" + uids.size()
                    + " names=" + names.size() + " enabled=" + enabled);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": no persisted config ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static synchronized void apply(String raw, boolean en) {
        Set<String> u = new HashSet<>();
        Set<String> n = new HashSet<>();
        Set<String> feats = null;
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("features=")) {
                    feats = new HashSet<>();
                    String v = line.substring(9).trim();
                    if (!v.isEmpty()) {
                        for (String k : v.split(",")) {
                            if (!k.trim().isEmpty()) feats.add(k.trim());
                        }
                    }
                    continue;
                }
                if (line.startsWith("topbar=")) {
                    // 缺省行保持默认 true；仅显式 topbar=0 表示恢复显示
                    topbarHidden = !"0".equals(line.substring(7).trim());
                    continue;
                }
                if (line.startsWith("visitor=")) {
                    visitorHidden = !"0".equals(line.substring(8).trim());
                    continue;
                }
                if (line.startsWith("interact=")) {
                    interactHidden = !"0".equals(line.substring(9).trim());
                    continue;
                }
                if (line.startsWith("blockenter=")) {
                    blockEnterHidden = !"0".equals(line.substring(11).trim());
                    continue;
                }
                if (line.startsWith("profiletabon=")) {
                    profileTabEnabled = !"0".equals(line.substring(13).trim());
                    continue;
                }
                if (line.startsWith("profiletab=")) {
                    Set<String> pt = new HashSet<>();
                    String praw2 = line.substring(11).trim();
                    if (!praw2.isEmpty()) {
                        for (String one : praw2.split("\\|")) {
                            String t = one.trim();
                            if (!t.isEmpty()) pt.add(t);
                        }
                    }
                    profileTabNames = pt;
                    continue;
                }
                if (line.startsWith("profileentryon=")) {
                    profileEntryEnabled = !"0".equals(line.substring(15).trim());
                    continue;
                }
                if (line.startsWith("profileentry=")) {
                    Set<String> pn = new HashSet<>();
                    String praw = line.substring(13).trim();
                    if (!praw.isEmpty()) {
                        for (String one : praw.split("\\|")) {
                            String nm = one.trim();
                            if (!nm.isEmpty()) pn.add(nm);
                        }
                    }
                    profileEntryNames = pn;
                    continue;
                }
                if (line.startsWith("cleanstorage=")) {
                    cleanStorageHidden = !"0".equals(line.substring(13).trim());
                    continue;
                }
                if (line.startsWith("watchhistory=")) {
                    menuWatchHistoryHidden = !"0".equals(line.substring(13).trim());
                    continue;
                }
                if (line.startsWith("sidebarhideon=")) {
                    sidebarHideEnabled = !"0".equals(line.substring(14).trim());
                    continue;
                }
                if (line.startsWith("sidebarhide2on=")) {
                    sidebarHideRightEnabled = !"0".equals(line.substring(15).trim());
                    continue;
                }
                if (line.startsWith("sidebarhide2=")) {
                    Set<String> sn2 = new HashSet<>();
                    String raw2 = line.substring(13).trim();
                    if (!raw2.isEmpty()) {
                        for (String one : raw2.split("\\|")) {
                            String nm = one.trim();
                            if (!nm.isEmpty()) sn2.add(nm);
                        }
                    }
                    sidebarHideRightNames = sn2;
                    continue;
                }
                if (line.startsWith("sidebarhide=")) {
                    Set<String> sn = new HashSet<>();
                    String snraw = line.substring(12).trim();
                    if (!snraw.isEmpty()) {
                        for (String one : snraw.split("\\|")) {
                            String nm = one.trim();
                            if (!nm.isEmpty()) sn.add(nm);
                        }
                    }
                    sidebarHideNames = sn;
                    continue;
                }
                if (line.startsWith("tempdisplay=")) {
                    tempDisplayEnabled = !"0".equals(line.substring(12).trim());
                    continue;
                }
                if (line.startsWith("lpdelay=")) {
                    try { longPressMs = clampDelay(Integer.parseInt(line.substring(8).trim())); }
                    catch (Throwable ignored) { }
                    continue;
                }
                if (line.startsWith("lptipon=")) {
                    tempTipOn = cleanTip(line.substring(8));
                    continue;
                }
                if (line.startsWith("lptipoff=")) {
                    tempTipOff = cleanTip(line.substring(9));
                    continue;
                }
                if (line.startsWith("notifymask=")) {
                    notifyMaskEnabled = !"0".equals(line.substring(11).trim());
                    continue;
                }
                // 兼容旧配置：定时功能已移除，忽略历史遗留的 sched* 行，
                // notifytitle/notifytext 为伪装通知遗留，忽略；
                // 避免被误读成一个 uid
                if (line.startsWith("schedon=") || line.startsWith("schedoff=")
                        || line.startsWith("schedapplied=")) {
                    continue;
                }
                String[] parts = line.split(",");
                String uid = parts[0].trim();
                String name = parts.length > 1 ? parts[1].trim() : "";
                if (!uid.isEmpty()) u.add(uid);
                if (!name.isEmpty()) n.add(name);
            }
        }
        uids = u;
        names = n;
        if (feats != null) {
            features = feats; // 显式 features= 行（包括空行）以用户保存为准
        } else {
            // 旧版本配置没有 features= 行：升级保留全部功能开启。
            // 全新安装根本没有配置文件，features 保持初始空集合（默认全关）。
            features = new java.util.HashSet<>(java.util.Arrays.asList(
                    "session", "search", "share", "invite", "relation"));
        }
        enabledSaved = en;
        enabled = en;
    }

    /** 当前配置序列化（写回文件/广播用）。 */
    private static String serializeEntries() {
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> f = features;
        if (f != null) {
            sb.append("features=");
            boolean first = true;
            for (String k : f) {
                if (!first) sb.append(',');
                sb.append(k);
                first = false;
            }
            sb.append('\n');
        }
        sb.append("topbar=").append(topbarHidden ? '1' : '0').append('\n');
        sb.append("visitor=").append(visitorHidden ? '1' : '0').append('\n');
        sb.append("interact=").append(interactHidden ? '1' : '0').append('\n');
        sb.append("blockenter=").append(blockEnterHidden ? '1' : '0').append('\n');
        sb.append("cleanstorage=").append(cleanStorageHidden ? '1' : '0').append('\n');
        sb.append("profiletabon=").append(profileTabEnabled ? '1' : '0').append('\n');
        StringBuilder ptb = new StringBuilder();
        for (String nm : profileTabNames) {
            if (ptb.length() > 0) ptb.append('|');
            ptb.append(nm);
        }
        sb.append("profiletab=").append(ptb).append('\n');
        sb.append("profileentryon=").append(profileEntryEnabled ? '1' : '0').append('\n');
        StringBuilder peb = new StringBuilder();
        for (String nm : profileEntryNames) {
            if (peb.length() > 0) peb.append('|');
            peb.append(nm);
        }
        sb.append("profileentry=").append(peb).append('\n');
        sb.append("watchhistory=").append(menuWatchHistoryHidden ? '1' : '0').append('\n');
        sb.append("sidebarhideon=").append(sidebarHideEnabled ? '1' : '0').append('\n');
        StringBuilder shb = new StringBuilder();
        for (String nm : sidebarHideNames) {
            if (shb.length() > 0) shb.append('|');
            shb.append(nm);
        }
        sb.append("sidebarhide=").append(shb).append('\n');
        sb.append("sidebarhide2on=").append(sidebarHideRightEnabled ? '1' : '0').append('\n');
        StringBuilder shb2 = new StringBuilder();
        for (String nm : sidebarHideRightNames) {
            if (shb2.length() > 0) shb2.append('|');
            shb2.append(nm);
        }
        sb.append("sidebarhide2=").append(shb2).append('\n');
        sb.append("tempdisplay=").append(tempDisplayEnabled ? '1' : '0').append('\n');
        sb.append("lpdelay=").append(longPressMs).append('\n');
        sb.append("lptipon=").append(cleanTip(tempTipOn)).append('\n');
        sb.append("lptipoff=").append(cleanTip(tempTipOff)).append('\n');
        sb.append("notifymask=").append(notifyMaskEnabled ? '1' : '0').append('\n');
        synchronized (FRIENDS) {
            // 名单以 uid,name 形式保存（去重）
        }
        java.util.Set<String> seen = new HashSet<>();
        for (String uid : uids) {
            sb.append(uid).append(",\n");
            seen.add(uid);
        }
        for (String name : names) {
            sb.append(',').append(name).append('\n');
        }
        return sb.toString();
    }

    // ===== 供抖音内设置面板调用的 API =====

    /** 面板显示的是持久设置；临时关闭只在内部生效，不改写用户保存的开关。 */
    static boolean isEnabled() {
        return enabledSaved;
    }

    static boolean isTempPaused() {
        return tempPaused;
    }

    static boolean isTempDisplayEnabled() { return tempDisplayEnabled; }

    static void setTempDisplayEnabled(boolean v) {
        tempDisplayEnabled = v;
        if (!v && tempPaused) {
            tempPaused = false;
            clearAllMutatedBackups();
            restoreAllCollapsedRows();
        }
        persistCurrent();
    }

    static int getLongPressDelay() { return longPressMs; }

    static String getTempTipOn() { return tempTipOn; }

    static String getTempTipOff() { return tempTipOff; }

    private static int clampDelay(int v) {
        return v < 100 ? 100 : (v > 5000 ? 5000 : v);
    }

    /** 提示词是单行配置，换行/制表符会破坏 key=value 文件结构。 */
    private static String cleanTip(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
    }

    static void setLongPressOptions(int delay, String tipOn, String tipOff) {
        longPressMs = clampDelay(delay);
        tempTipOn = cleanTip(tipOn);
        tempTipOff = cleanTip(tipOff);
        persistCurrent();
        XposedBridge.log(TAG + ": longpress options delay=" + longPressMs
                + " tipOn=" + tempTipOn + " tipOff=" + tempTipOff);
    }

    /** 长按消息页标题：临时暂停/恢复隐藏，仅本次进程有效。 */
    static void toggleTempPause(Context ctx) {
        // 总开关关闭时没有任何隐藏需要临时恢复，长按完全不生效
        if (!enabledSaved) return;
        if (!tempDisplayEnabled) {
            if (ctx != null) android.widget.Toast.makeText(ctx,
                    "临时显示已关闭（#dy 面板「恢复」组可开启）",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        tempPaused = !tempPaused;
        enabled = enabledSaved;
        XposedBridge.log(TAG + ": temp pause=" + tempPaused + " effective=" + enabled
                + " delay=" + longPressMs);
        if (tempPaused) {
            clearAllMutatedBackups();
            restoreAllCollapsedRows();
            applyTopBarVisibility(null);
            restoreProfileEntries();
            restoreSidebarEntries();
        }
        String tip = tempPaused
                ? (tempTipOn.isEmpty() ? "已临时恢复：会话/进入拦截/分享面板暂停隐藏（再长按恢复）" : tempTipOn)
                : (tempTipOff.isEmpty() ? "已恢复隐藏" : tempTipOff);
        if (ctx != null) {
            android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static boolean isTopbarHidden() {
        return topbarHidden;
    }

    static void setTopbarHidden(boolean hide) {
        topbarHidden = hide;
        persistCurrent();
        // 立即对已附加的头像条生效：取消隐藏则恢复原样
        if (!hide) {
            synchronized (TOPBAR_BARS) {
                for (android.view.View bar : new java.util.ArrayList<>(TOPBAR_BARS.keySet())) {
                    int[] orig = TOPBAR_BARS.remove(bar);
                    if (orig == null) continue;
                    bar.setVisibility(orig[0]);
                    android.view.ViewGroup.LayoutParams lp = bar.getLayoutParams();
                    if (lp != null) {
                        lp.width = orig[1];
                        lp.height = orig[2];
                        bar.setLayoutParams(lp);
                    }
                }
            }
        }
    }

    // ===== v7.0 新功能独立开关 API =====
    // ===== v10 通知伪装开关 API =====
    static boolean isNotifyMaskEnabled() { return notifyMaskEnabled; }
    static void setNotifyMaskEnabled(boolean v) { notifyMaskEnabled = v; persistCurrent(); }

    static boolean isVisitorHidden() { return visitorHidden; }
    static void setVisitorHidden(boolean v) { visitorHidden = v; persistCurrent(); }

    static boolean isInteractHidden() { return interactHidden; }
    static void setInteractHidden(boolean v) { interactHidden = v; persistCurrent(); }

    static boolean isBlockEnterHidden() { return blockEnterHidden; }
    static void setBlockEnterHidden(boolean v) { blockEnterHidden = v; persistCurrent(); }

    static boolean isProfileEntryEnabled() { return profileEntryEnabled; }

    static void setProfileEntryEnabled(boolean on) {
        profileEntryEnabled = on;
        persistCurrent();
        XposedBridge.log(TAG + ": profile-entry enabled=" + on);
        if (!on) restoreProfileEntries();
    }

    static java.util.List<String> getProfileEntryNameList() {
        java.util.List<String> list = new java.util.ArrayList<>(profileEntryNames);
        java.util.Collections.sort(list);
        return list;
    }

    /** v12：当前已发现的入口（标识 id:xxx / type:x）。按发现顺序返回。 */
    static java.util.List<String> getDiscoveredProfileEntryKeys() {
        synchronized (discoveredProfileEntries) {
            return new java.util.ArrayList<>(discoveredProfileEntries.keySet());
        }
    }

    /** v12：入口显示名（按标识）。 */
    static String getDiscoveredProfileEntryLabel(String key) {
        synchronized (discoveredProfileEntries) {
            return discoveredProfileEntries.get(key);
        }
    }

    /** v12：清空已发现的入口清单（面板「重新扫描」用）。 */
    static void clearDiscoveredProfileEntries() {
        synchronized (discoveredProfileEntries) {
            discoveredProfileEntries.clear();
        }
    }

    static void setProfileEntryNames(java.util.List<String> list) {
        Set<String> ns = new HashSet<>();
        if (list != null) {
            for (String one : list) {
                if (one == null) continue;
                String t = one.trim();
                if (!t.isEmpty()) ns.add(t);
            }
        }
        profileEntryNames = ns;
        // v12：配置了逐个隐藏名单就自动启用该功能，避免总开关没开导致不生效
        if (!ns.isEmpty()) profileEntryEnabled = true;
        persistCurrent();
        restoreProfileEntries();
        XposedBridge.log(TAG + ": profile-entry names set " + ns + " enabled=" + profileEntryEnabled);
    }

    // ---- 个人主页标签逐个隐藏 ----
    static boolean isProfileTabEnabled() { return profileTabEnabled; }

    static void setProfileTabEnabled(boolean on) {
        profileTabEnabled = on;
        persistCurrent();
    }

    static java.util.List<String> getProfileTabNameList() {
        java.util.List<String> list = new java.util.ArrayList<>(profileTabNames);
        java.util.Collections.sort(list);
        return list;
    }

    static java.util.List<String> getDiscoveredProfileTabKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        keys.addAll(DEFAULT_PROFILE_TABS.keySet());
        synchronized (discoveredProfileTabs) {
            keys.addAll(discoveredProfileTabs.keySet());
        }
        return new java.util.ArrayList<String>(keys);
    }

    static String getDiscoveredProfileTabLabel(String key) {
        String label = null;
        synchronized (discoveredProfileTabs) {
            label = discoveredProfileTabs.get(key);
        }
        if (label == null || label.isEmpty()) label = DEFAULT_PROFILE_TABS.get(key);
        return label;
    }

    static void clearDiscoveredProfileTabs() {
        synchronized (discoveredProfileTabs) {
            discoveredProfileTabs.clear();
        }
    }

    static void setProfileTabNames(java.util.List<String> list) {
        Set<String> ns = new HashSet<>();
        if (list != null) {
            for (String one : list) {
                if (one == null) continue;
                String t = one.trim();
                if (!t.isEmpty()) ns.add(t);
            }
        }
        profileTabNames = ns;
        if (!ns.isEmpty()) profileTabEnabled = true;
        persistCurrent();
        XposedBridge.log(TAG + ": profile-tab names set " + ns + " enabled=" + profileTabEnabled);
    }

    static boolean isCleanStorageHidden() { return cleanStorageHidden; }

    static void setCleanStorageHidden(boolean hide) {
        cleanStorageHidden = hide;
        persistCurrent();
    }

    static boolean isMenuWatchHistoryHidden() { return menuWatchHistoryHidden; }

    static void setMenuWatchHistoryHidden(boolean hide) {
        menuWatchHistoryHidden = hide;
        persistCurrent();
        // 关闭时立即恢复「我」页菜单里被 GONE 的行
        if (!hide) {
            synchronized (SIDEBAR_HIDDEN) {
                for (java.util.Map.Entry<android.view.View, int[]> e
                        : new java.util.ArrayList<>(SIDEBAR_HIDDEN.entrySet())) {
                    try {
                        android.view.View v = e.getKey();
                        int[] orig = e.getValue();
                        if (v != null && orig != null) v.setVisibility(orig[0]);
                    } catch (Throwable ignored) {
                    }
                }
                SIDEBAR_HIDDEN.clear();
            }
        }
    }

    // ===== 侧边栏入口自定义删除 =====

    static boolean isSidebarHideEnabled() { return sidebarHideEnabled; }

    static void setSidebarHideEnabled(boolean on) {
        sidebarHideEnabled = on;
        persistCurrent();
        if (!on) restoreSidebarEntries();
    }

    /** 当前删除名单（按字母序，便于面板展示） */
    static java.util.List<String> getSidebarHideNameList() {
        java.util.List<String> list = new java.util.ArrayList<>(sidebarHideNames);
        java.util.Collections.sort(list);
        return list;
    }

    /** 整体替换删除名单（面板编辑后调用） */
    static void setSidebarHideNames(java.util.List<String> list) {
        Set<String> ns = new HashSet<>();
        if (list != null) {
            for (String one : list) {
                if (one == null) continue;
                String t = one.trim();
                if (!t.isEmpty()) ns.add(t);
            }
        }
        sidebarHideNames = ns;
        persistCurrent();
        restoreSidebarEntries();
    }

    // ===== 右滑侧边栏自定义删除 =====

    static boolean isSidebarHideRightEnabled() { return sidebarHideRightEnabled; }

    static void setSidebarHideRightEnabled(boolean on) {
        sidebarHideRightEnabled = on;
        persistCurrent();
        if (!on) restoreSidebarEntries();
    }

    static java.util.List<String> getSidebarHideRightNameList() {
        java.util.List<String> list = new java.util.ArrayList<>(sidebarHideRightNames);
        java.util.Collections.sort(list);
        return list;
    }

    static void setSidebarHideRightNames(java.util.List<String> list) {
        Set<String> ns = new HashSet<>();
        if (list != null) {
            for (String one : list) {
                if (one == null) continue;
                String t = one.trim();
                if (!t.isEmpty()) ns.add(t);
            }
        }
        sidebarHideRightNames = ns;
        persistCurrent();
        restoreSidebarEntries();
    }

    /** 把已移除的网格项按原位置插回，并重新排列 */
    static void restoreSidebarEntries() {
        synchronized (SIDEBAR_REMOVED) {
            for (java.util.Map.Entry<android.view.View, Object[]> e
                    : new java.util.ArrayList<>(SIDEBAR_REMOVED.entrySet())) {
                try {
                    android.view.View v = e.getKey();
                    Object[] rec = e.getValue();
                    android.view.ViewGroup g = (android.view.ViewGroup) rec[0];
                    int idx = (Integer) rec[1];
                    int cols = rec.length > 2 ? (Integer) rec[2] : 0;
                    if (v == null || g == null || v.getParent() != null) continue;
                    int n = Math.max(0, Math.min(idx, g.getChildCount()));
                    g.addView(v, n);
                    v.setVisibility(android.view.View.VISIBLE);
                    if (g instanceof android.widget.GridLayout) {
                        relayoutGrid((android.widget.GridLayout) g, cols);
                    }
                } catch (Throwable ignored) {
                }
            }
            SIDEBAR_REMOVED.clear();
        }
    }

    /** 临时显示期间只暂停「会话 / 禁止进入 / 分享面板头像条」，其余隐藏继续生效。 */
    private static boolean tempAllows(String key) {
        return !tempPaused || key == null
                || !(("session".equals(key)) || ("share".equals(key))
                || ("blockEnter".equals(key)));
    }

    static boolean isFeatureOn(String key) {
        if (!tempAllows(key)) return false;
        java.util.Set<String> f = features;
        if (f == null) return true;
        if (f.contains(key)) return true;
        // 兼容旧配置：关注/粉丝/互关/朋友已合并为 relation，任一旧 key 开即视为开
        if ("relation".equals(key)
                && (f.contains("following") || f.contains("follower") || f.contains("mutual"))) {
            return true;
        }
        return false;
    }

    static int hiddenCount() {
        return uids.size();
    }

    static boolean isUidHidden(String uid) {
        return uids.contains(uid);
    }

    static java.util.List<String[]> getFriends() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        synchronized (FRIENDS) {
            for (java.util.Map.Entry<String, String> e : FRIENDS.entrySet()) {
                out.add(new String[]{e.getKey(), e.getValue()});
            }
        }
        return out;
    }

    static void setHiddenContacts(java.util.List<String[]> chosen) {
        Set<String> u = new HashSet<>();
        Set<String> n = new HashSet<>();
        for (String[] c : chosen) {
            if (c[0] != null && !c[0].isEmpty()) u.add(c[0]);
            if (c.length > 1 && c[1] != null && !c[1].isEmpty()) n.add(c[1]);
        }
        uids = u;
        names = n;
        persistCurrent();
    }

    static void saveConfig(boolean en, java.util.List<String> feats) {
        Context ctx = APP_CTX;
        if (ctx == null) return;
        boolean ok = saveAll(ctx, en, feats, topbarHidden, visitorHidden, interactHidden,
                blockEnterHidden, cleanStorageHidden, profileEntryEnabled);
        if (!ok) XposedBridge.log(TAG + ": saveConfig failed");
    }

    /**
     * 面板专用：直接用当前界面上读到的值写盘，不依赖任何"内存里旧的 features"。
     * 一次提交只写一次盘，成功/失败都如实返回。
     */
    static boolean saveAll(Context ctx, boolean en, java.util.List<String> feats,
                           boolean topbar, boolean visitor, boolean interact,
                           boolean blockEnter, boolean cleanStorage, boolean profileEntry) {
        SUPPRESS_PERSIST = false;
        features = new HashSet<>(feats);
        enabledSaved = en;
        // 面板保存只写持久设置，不碰临时显示态：临时态是会话内的叠加状态，
        // 面板里改任何开关都不应静默清掉它，否则用户再长按的方向会错乱
        // （tempPaused 被清空 → 下次长按 !tempPaused 又翻成 true，"恢复"失效）。
        // 仅在真正关闭总开关时清理临时态（此时没有需要恢复的隐藏）。
        if (!en) tempPaused = false;
        enabled = en;
        topbarHidden = topbar;
        visitorHidden = visitor;
        interactHidden = interact;
        blockEnterHidden = blockEnter;
        cleanStorageHidden = cleanStorage;
        profileEntryEnabled = profileEntry;
        // 配置变更（尤其是关闭功能）后立即撤销所有隐藏副作用
        if (!en || !features.contains("share")) {
            clearAllMutatedBackups();
        }
        String pn = currentProcessName();
        if (!pn.isEmpty() && !TARGET_PKG.equals(pn)) {
            XposedBridge.log(TAG + ": saveAll skipped in " + pn);
            return false;
        }
        boolean ok = persistConfig(ctx, serializeEntries(), en);
        if (ok) {
            XposedBridge.log(TAG + ": config saved DIRECT features=" + features
                    + " interact=" + interact + " topbar=" + topbar
                    + " profileentry=" + profileEntry
                    + " tempPaused=" + tempPaused + " @ saveAll");
        } else {
            XposedBridge.log(TAG + ": config save FAILED @ saveAll");
        }
        return ok;
    }

    /** 当前进程名（只允许主进程写配置，避免多进程互相覆盖） */
    private static String currentProcessName() {
        try {
            return (String) android.app.Application.class
                    .getMethod("getProcessName").invoke(null);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object obj = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            Object pn = XposedHelpers.callMethod(obj, "getProcessName");
            return pn == null ? "" : String.valueOf(pn);
        } catch (Throwable ignored) {
        }
        return "";
    }

    static void beginBatch() { SUPPRESS_PERSIST = true; }

    static void endBatch() { SUPPRESS_PERSIST = false; }

    private static void persistCurrent() {
        Context ctx = APP_CTX;
        if (ctx == null) return;
        if (SUPPRESS_PERSIST) return;
        // processWriteGuard: 只有主进程可以写配置，其余进程（sandboxed / minigame 等）
        // 内存里可能是旧值，写盘会把主进程刚保存的配置整份覆盖（"保存后重启全恢复"的根因）
        String pn = currentProcessName();
        if (!pn.isEmpty() && !TARGET_PKG.equals(pn)) {
            XposedBridge.log(TAG + ": persist skipped in " + pn);
            return;
        }
        String entries = serializeEntries();
        boolean ok = persistConfig(ctx, entries, enabledSaved);
        if (!ok) return; // 写失败就不再打"config saved"，避免误判为保存成功
        XposedBridge.log(TAG + ": config saved uids=" + uids.size()
                + " names=" + names.size() + " features=" + features
                + " proc=" + currentProcessName() + " @ " + callerTag());
    }

    /** 诊断用：写盘调用栈（最近 4 帧），用于定位是谁把 features 写成空 */
    private static String callerTag() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < st.length && n < 4; i++) {
                String cn = st[i].getClassName();
                String mn = st[i].getMethodName();
                if (cn.endsWith("MainHook") && (mn.equals("callerTag")
                        || mn.equals("persistCurrent"))) continue;
                if (cn.equals("java.lang.Thread") || cn.startsWith("com.android.internal")
                        || cn.startsWith("android.os.") || cn.startsWith("de.robv")) continue;
                int dot = cn.lastIndexOf('.');
                if (dot > 0) cn = cn.substring(dot + 1);
                if (n > 0) sb.append(" < ");
                sb.append(cn).append('.').append(mn).append(':').append(st[i].getLineNumber());
                n++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void filterList(XC_MethodHook.MethodHookParam param, int argIdx, String label) {
        try {
            if (!enabled || uids.isEmpty()) return;
            String fk = featureOf(label);
            // 无归属 label（如通用 base-adapter:LJLLLLLL）：按 thisObject 实际类名推断功能开关，
            // 避免通用适配器绕过功能开关（关 share 后分享页仍隐藏的根因）。
            if (fk == null && param.thisObject != null) {
                fk = featureOfOwner(param.thisObject.getClass().getName());
            }
            if (fk != null && !isFeatureOn(fk)) return;
            List<?> list = (List<?>) param.args[argIdx];
            if (list == null || list.isEmpty()) return;
            String owner = param.thisObject == null ? "?"
                    : param.thisObject.getClass().getName();
            ArrayList<Object> keep = new ArrayList<>(list.size());
            int removed = 0;
            boolean record = label != null && label.contains("mate-sort");
            boolean isSession = "session".equals(label);
            for (Object item : list) {
                if (record) recordFriend(item);
                if (isHidden(item)) {
                    removed++;
                    // v10.6：隐藏会话出现在消息列表刷新中 → 检测未读增量点亮标题红点
                    if (isSession) checkSessionDot(item);
                } else {
                    keep.add(item);
                }
            }
            if (removed > 0) {
                param.args[argIdx] = keep;
                XposedBridge.log(TAG + ": filtered " + removed + " item(s) [" + label
                        + " by " + owner + "]");
            } else {
                // debug disabled in clean build

            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": filter error [" + label + "]: " + t);
        }
    }

    /**
     * 粉丝/关注页搜索结果过滤：dB(Function1) 执行后 state 已被 setValue，
     * 这里从 VM 的 TA().getValue() 拿到最终 state，取其 LJII().LIZ 展示列表，
     * 原地按 uid 删除隐藏联系人。C162410bS.LIZ 通常是 ArrayList，原地删除可保住引用。
     */
    private static void filterRelationState(Object vm, String label) {
        try {
            if (vm == null || !enabled || uids.isEmpty()) return;
            String fk = featureOf(label);
            if (fk != null && !isFeatureOn(fk)) return;
            Object liveData = XposedHelpers.callMethod(vm, "TA");
            if (liveData == null) return;
            Object state = XposedHelpers.callMethod(liveData, "getValue");
            if (state == null) return;
            Object rawListState = XposedHelpers.callMethod(state, "LJII");
            if (rawListState == null) return;
            Object listObj = XposedHelpers.getObjectField(rawListState, "LIZ");
            if (!(listObj instanceof java.util.List)) return;
            java.util.List<?> list = (java.util.List<?>) listObj;
            if (list.isEmpty()) return;
            java.util.ArrayList<Object> drop = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null && isHidden(item)) drop.add(item);
            }
            // 节流诊断：确认搜索路径是否触发、元素类型与 uid 判定
            if (System.currentTimeMillis() - lastRelDiag > 8000) {
                lastRelDiag = System.currentTimeMillis();
                String t0 = list.isEmpty() ? "-" : list.get(0).getClass().getName();
                String u0 = list.isEmpty() ? "-" : String.valueOf(resolveUid(list.get(0)));
                XposedBridge.log(TAG + ": rel-diag [" + label + "] size=" + list.size()
                        + " drop=" + drop.size() + " type0=" + t0 + " uid0=" + u0);
            }
            if (drop.isEmpty()) return;
            try {
                list.removeAll(drop);
            } catch (Throwable ignored) {
                // 不可变 List 时退化为反射替换字段（多数为 ArrayList，不会走到这里）
            }
            XposedBridge.log(TAG + ": filtered " + drop.size() + " item(s) [" + label + "]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": relation-state filter error [" + label + "]: " + t);
        }
    }

    /** 大列表转储：找第一个带真实 payload 的元素并打印其字段（节流 15 秒）。 */
    private static void dumpFields(String label, List<?> list) {
        try {
            long now = System.currentTimeMillis();
            Long last;
            synchronized (DEBUG_THROTTLE) {
                last = DEBUG_THROTTLE.get("dump:" + label);
                if (last != null && now - last < 15000) return;
                DEBUG_THROTTLE.put("dump:" + label, now);
            }
            Object target = null;
            for (Object it : list) {
                if (it == null) continue;
                if (hasObjectPayload(it)) {
                    target = it;
                    break;
                }
            }
            if (target == null && !list.isEmpty()) target = list.get(0);
            if (target == null) return;
            StringBuilder sb = new StringBuilder("dump[").append(label).append("] ")
                    .append(target.getClass().getName()).append(" {");
            int n = 0;
            for (Field f : getFieldsCached(target.getClass())) {
                if (n >= 10) break;
                try {
                    Object v = f.get(target);
                    sb.append(' ').append(f.getName()).append('=');
                    if (v == null) {
                        sb.append("null");
                    } else if (v.getClass().isEnum()) {
                        sb.append(v.getClass().getName()).append('(').append(v).append(')');
                    } else {
                        sb.append(v.getClass().getName());
                        if (v instanceof CharSequence) {
                            String s = v.toString();
                            if (s.length() > 24) s = s.substring(0, 24) + "..";
                            sb.append("(\"").append(s).append("\")");
                        }
                    }
                    n++;
                } catch (Throwable ignored) {
                }
            }
            sb.append(" }");
            // 附带 payload 内部字段
            try {
                for (Field f : getFieldsCached(target.getClass())) {
                    Object v = f.get(target);
                    if (v == null || v.getClass().isEnum() || v instanceof CharSequence) continue;
                    StringBuilder sb2 = new StringBuilder(" | ")
                            .append(f.getName()).append('(')
                            .append(v.getClass().getName()).append("){");
                    int k = 0;
                    for (Field f2 : getFieldsCached(v.getClass())) {
                        if (k >= 14) break;
                        try {
                            Object v2 = f2.get(v);
                            sb2.append(' ').append(f2.getName()).append('=');
                            if (v2 == null) {
                                sb2.append("null");
                            } else if (v2 instanceof CharSequence) {
                                String s = v2.toString();
                                if (s.length() > 20) s = s.substring(0, 20) + "..";
                                sb2.append('"').append(s).append('"');
                            } else if (v2.getClass().isEnum()) {
                                sb2.append(v2);
                            } else if (v2 instanceof Number) {
                                sb2.append(v2);
                            } else {
                                sb2.append(v2.getClass().getSimpleName());
                            }
                            k++;
                        } catch (Throwable ignored) {
                        }
                    }
                    sb.append(sb2).append(" }");
                    break;
                }
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + ": " + sb);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasObjectPayload(Object it) {
        try {
            for (Field f : getFieldsCached(it.getClass())) {
                Object v = f.get(it);
                if (v == null) continue;
                Class<?> c = v.getClass();
                if (c.isEnum() || v instanceof CharSequence) continue;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** label → 功能开关 key */
    private static String featureOf(String label) {
        if (label == null) return null;
        if (label.startsWith("session")) return "session";
        // 关注/粉丝/互关/朋友四列表共用 relation 开关
        if (label.startsWith("following")) return "relation";
        if (label.startsWith("follower")) return "relation";
        if (label.startsWith("mate")) return "relation";
        if (label.startsWith("search")) return "search";
        if (label.startsWith("share") || label.startsWith("shvm")) return "share";
        if (label.startsWith("mask") || label.startsWith("picker")) return "share";
        if (label.startsWith("online") || label.startsWith("skylight")) return "skylight";
        if (label.startsWith("invite")) return "invite";
        return null;
    }

    /** 按对象实际类名推断功能开关归属，供无归属 label 的通用 hook 使用。 */
    private static String featureOfOwner(String cn) {
        if (cn == null) return null;
        // 分享/邀请体系（含 IM 分享、Picker 选人、mask 分享弹层）
        if (cn.contains("share") || cn.contains("Share") || cn.contains("picker")
                || cn.contains("Picker") || cn.contains("invite") || cn.contains("Invite")) {
            return "share";
        }
        // 粉丝/关注/互关/朋友（含搜索结果 adapter）
        if (cn.contains("follower") || cn.contains("Follower")
                || cn.contains("following") || cn.contains("Following")
                || cn.contains("mate") || cn.contains("Mate")) {
            return "relation";
        }
        if (cn.contains("session") || cn.contains("Session")) return "session";
        return null;
    }

    /** 无命中时按 label 节流输出首元素类型，用于诊断 hook 是否被调用及元素类型。 */
    private static void diagFollowerSearch(String info) {
        if (System.currentTimeMillis() - lastRelSearchDiag > 6000) {
            lastRelSearchDiag = System.currentTimeMillis();
            XposedBridge.log(TAG + ": fs-diag " + info);
        }
    }

    private static void debugSample(String label, List<?> list, String owner) {
        try {
            long now = System.currentTimeMillis();
            Long last;
            synchronized (DEBUG_THROTTLE) {
                last = DEBUG_THROTTLE.get(label);
                if (last != null && now - last < 10000) return;
                DEBUG_THROTTLE.put(label, now);
            }
            StringBuilder sb = new StringBuilder("dbg[").append(label).append("] by ")
                    .append(owner).append(" size=").append(list.size()).append(" types:");
            int shown = 0;
            Set<String> seen = new HashSet<>();
            for (Object it : list) {
                if (it == null || shown >= 4) break;
                String cn = it.getClass().getName();
                if (!seen.add(cn)) continue;
                Object payload = matchAllowed(it, it.getClass(), 0);
                sb.append(' ').append(cn);
                if (payload != it) {
                    sb.append("->").append(payload == null ? "null"
                            : payload.getClass().getName());
                }
                shown++;
            }
            XposedBridge.log(TAG + ": " + sb);
        } catch (Throwable ignored) {
        }
    }

    /**
     * v6：所有入口统一走「真实 uid」判定，彻底移除昵称/文本匹配。
     * 与一起看邀请面板共用同一套 uid 提取：先按类型取官方 getter，再递归拆包装；
     * 只有 uid 命中隐藏集合才返回 true。names 仅用于旧配置兼容，不再参与判定。
     */
    private static boolean isHidden(Object item) {
        return resolveUid(item) != null;
    }

    /**
     * 返回对象里第一个「命中隐藏名单」的真实 uid；没命中返回 null。全程不读昵称。
     * 关键：某些对象（如 CommonSessionItemState）的 getId() 返回会话 id 而非 uid，
     * 若拿到第一个 uid-like 字符串就返回，会阻断对内部真正 uid 的继续查找 →
     * 消息列表/搜索态失效。因此每条候选都先验证是否在名单里。
     */
    private static String resolveUid(Object item) {
        if (item == null) return null;
        String cn = item.getClass().getName();
        if (cn.contains("IMMember")) {
            String s = tryStr(item, new String[]{"LJIJJLI", "getUidS", "getUid", "getSingleId"});
            if (isHiddenUid(s)) return s;
        }
        // 粉丝/关注搜索结果元素：X.C241410oC（运行混淆名 X.10oC / X.10oM 等），
        // 官方 getter LJIJ()=uid、LJIILJJIL()=secUid。显式取值，避免深度反射扫描被其他字段干扰。
        // 注意：不能硬编码 equals jadx 重命名名，须按运行时混淆名特征匹配。
        if (cn.startsWith("X.10o") || cn.contains("C241410oC")) {
            String s = tryStr(item, new String[]{"LJIJ", "LJIILJJIL", "LJIIJ", "getUid", "getSecUid", "getUidS"});
            if (isHiddenUid(s)) return s;
        }
        Object o = matchAllowed(item, item.getClass(), 0);
        if (o != null) {
            String s = uidOfAllowed(o);
            if (isHiddenUid(s)) return s;
        }
        String deep = findUidDeep(item, item.getClass(), 0);
        if (deep != null) return deep;
        // 兜底：会话/搜索等只带昵称的 VO，用真实好友池把昵称反查成 uid
        return resolveUidByPool(item);
    }

    private static boolean isHiddenUid(String s) {
        return isRealUid(s) && uids.contains(s);
    }

    /** 从白名单联系人对象取 uid（IMUser 先转 User，避免首字母 getter 误判）。 */
    private static String uidOfAllowed(Object o) {
        if (o == null) return null;
        String cn = o.getClass().getName();
        if (T_IMUSER.equals(cn)) {
            Object u = convertImUser(o);
            if (u != null && u != o) return uidOfAllowed(u);
        }
        String[] getters = cn.contains("IMMember")
                ? new String[]{"LJIJJLI", "getUidS", "getUid", "getSingleId"}
                : new String[]{"getUid", "getUidS", "getSingleId", "getId", "getSecUid"};
        String s = tryStr(o, getters);
        return isRealUid(s) ? s : null;
    }

    private static boolean isRealUid(String s) {
        return s != null && s.length() >= 5;
    }

    /** 从任意 VO/包装里递归找 uid；字符串只在等于已隐藏 uid 时才认（绝不匹配昵称）。 */
    private static String findUidDeep(Object holder, Class<?> c, int depth) {
        if (depth > 4 || holder == null || c == null || c.isArray()) return null;
        if (DENY_TYPES.contains(c.getName())) return null;
        Field[] fields = getFieldsCached(c);
        for (Field f : fields) {
            try {
                Object v = f.get(holder);
                if (v == null || v == holder) continue;
                String s = uidFromValue(v, depth);
                if (s != null) return s;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String uidFromValue(Object v, int depth) {
        if (v == null) return null;
        if (v instanceof CharSequence) {
            String s = v.toString();
            // 只认「已经在隐藏名单里的 uid」；昵称即使等于名单里的名称也不命中
            return isRealUid(s) && uids.contains(s) ? s : null;
        }
        if (v instanceof java.util.Map || v.getClass().isArray()) return null;
        if (v instanceof java.util.Collection) {
            int n = 0;
            for (Object e : (java.util.Collection<?>) v) {
                if (e == null) continue;
                String s = uidFromValue(e, depth + 1);
                if (s != null) return s;
                if (++n >= 12) break;
            }
            return null;
        }
        Class<?> vc = v.getClass();
        if (isAllowedClass(vc, vc.getClassLoader())) {
            String s = uidOfAllowed(v);
            return isHiddenUid(s) ? s : null;
        }
        return findUidDeep(v, vc, depth + 1);
    }

    /**
     * 好友池反查：会话/搜索等 VO 只有昵称没有 uid（如 SimpleSessionItemState 只有 title）。
     * 用对象里的昵称到真实好友池 FRIENDS 反查 uid，再判断该 uid 是否在隐藏名单——
     * 本质仍是 uid 判定，且只有好友池里真实存在、同时被隐藏的昵称才命中，
     * 不会像旧的文本方案那样误伤任意同名字符串。
     */
    private static String resolveUidByPool(Object item) {
        if (item == null || uids.isEmpty()) return null;
        return findNameInPool(item, item.getClass(), 0);
    }

    private static String findNameInPool(Object holder, Class<?> c, int depth) {
        if (depth > 4 || holder == null || c == null || c.isArray()) return null;
        if (DENY_TYPES.contains(c.getName())) return null;
        Field[] fields = getFieldsCached(c);
        for (Field f : fields) {
            try {
                Object v = f.get(holder);
                if (v == null || v == holder) continue;
                String hit = nameInPoolValue(v, depth);
                if (hit != null) return hit;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String nameInPoolValue(Object v, int depth) {
        if (v == null) return null;
        if (v instanceof CharSequence) {
            String s = v.toString().trim();
            if (s.isEmpty() || s.length() > 40) return null;
            synchronized (FRIENDS) {
                for (java.util.Map.Entry<String, String> e : FRIENDS.entrySet()) {
                    String uid = e.getKey();
                    if (s.equals(e.getValue()) && uids.contains(uid)) return uid;
                }
            }
            return null;
        }
        if (v instanceof java.util.Map || v.getClass().isArray()) return null;
        if (v instanceof java.util.Collection) {
            int n = 0;
            for (Object e : (java.util.Collection<?>) v) {
                if (e == null) continue;
                String hit = nameInPoolValue(e, depth + 1);
                if (hit != null) return hit;
                if (++n >= 12) break;
            }
            return null;
        }
        return findNameInPool(v, v.getClass(), depth + 1);
    }

    /** 深度 dump 对象字段（诊断用）：类型+字段名=值，最多 3 层、每层 24 字段。 */
    private static void dumpObjectFields(Object o, StringBuilder sb, int depth) {
        if (o == null || depth > 2 || sb.length() > 1400) return;
        String cn = o.getClass().getName();
        sb.append('{').append(cn).append(':');
        Field[] fs = getFieldsCached(o.getClass());
        int n = 0;
        for (Field f : fs) {
            if (n >= 24 || sb.length() > 1400) break;
            try {
                Object v = f.get(o);
                if (v == null || v == o) continue;
                sb.append(f.getName()).append('=');
                if (v instanceof CharSequence) {
                    String s = v.toString();
                    sb.append('"').append(s.length() > 60 ? s.substring(0, 60) : s).append('"');
                    n++;
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                    n++;
                } else if (v instanceof java.util.Collection) {
                    java.util.Collection<?> col = (java.util.Collection<?>) v;
                    sb.append("List[").append(col.size()).append("]{");
                    int k = 0;
                    for (Object e : col) {
                        if (k >= 3) break;
                        if (e != null) sb.append(e.getClass().getSimpleName()).append(' ');
                        if (e instanceof CharSequence) {
                            String s = e.toString();
                            sb.append('"').append(s.length() > 40 ? s.substring(0, 40) : s).append('"');
                        }
                        k++;
                    }
                    sb.append('}');
                    n++;
                } else if (depth < 2) {
                    sb.append('(').append(v.getClass().getSimpleName()).append(')');
                    dumpObjectFields(v, sb, depth + 1);
                    n++;
                }
                sb.append(';');
            } catch (Throwable ignored) {
            }
        }
        sb.append('}');
    }

    /** 采样对象中的若干字符串（诊断用）。 */
    private static String sampleStrings(Object o, int max) {
        StringBuilder sb = new StringBuilder("[");
        collectStrings(o, o == null ? null : o.getClass(), 0, sb, max);
        return sb.append(']').toString();
    }

    private static int collectStrings(Object o, Class<?> c, int depth, StringBuilder sb, int max) {
        if (o == null || c == null || depth > 2) return 0;
        int n = 0;
        for (Field f : getFieldsCached(c)) {
            if (n >= max) return n;
            try {
                Object v = f.get(o);
                if (v instanceof CharSequence) {
                    String s = v.toString();
                    if (!s.isEmpty() && s.length() < 40) {
                        sb.append('"').append(s).append("\" ");
                        n++;
                        if (n >= max) return n;
                    }
                } else if (v instanceof java.util.Collection) {
                    int k = 0;
                    for (Object e : (java.util.Collection<?>) v) {
                        if (e instanceof CharSequence) {
                            String s = e.toString();
                            if (!s.isEmpty() && s.length() < 40) {
                                sb.append('"').append(s).append("\" ");
                                n++;
                            }
                        } else if (e != null) {
                            n += collectStrings(e, e.getClass(), depth + 1, sb, max - n);
                        }
                        if (n >= max || ++k >= 8) break;
                    }
                } else if (v != null && !(v instanceof java.util.Map)) {
                    n += collectStrings(v, v.getClass(), depth + 1, sb, max - n);
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    // v6：旧的 matchByStrings()（按昵称/任意字符串文本匹配）已移除。
    // 现在所有入口统一走 resolveUid() → uids.contains()，不再用昵称文本判定。

    private static Object convertImUser(Object o) {
        try {
            Method m = o.getClass().getMethod("l", o.getClass());
            m.setAccessible(true);
            return m.invoke(null, o);
        } catch (Throwable t) {
            try {
                Method m = o.getClass().getMethod("l", o.getClass());
                m.setAccessible(true);
                return m.invoke(o, o);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * 在对象（及其包装字段）中寻找白名单类型的联系人。
     * 规则：自身是白名单类 → 命中；SessionInfo → 取 LLIFFJFJJ() 的 IMUser；
     * 其他对象 → 深度扫描字段（黑名单容器整体跳过）。
     */
    private static Object matchAllowed(Object holder, Class<?> c, int depth) {
        if (depth > 3 || holder == null || c == null || c.isArray()) return null;
        String cn = c.getName();
        if (DENY_TYPES.contains(cn)) return null;
        if (isAllowedClass(c, holder.getClass().getClassLoader())) return holder;
        if (T_SESSION_INFO.equals(cn)) {
            Object u = tryObj(holder, "LLIFFJFJJ");
            if (u != null && isAllowedClass(u.getClass(), u.getClass().getClassLoader())) {
                return u;
            }
        }
        Field[] fields = getFieldsCached(c);
        for (Field f : fields) {
            try {
                Object v = f.get(holder);
                if (v == null || v == holder) continue;
                Object r = matchAllowed(v, v.getClass(), depth + 1);
                if (r != null) return r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object tryObj(Object o, String getter) {
        try {
            return o.getClass().getMethod(getter).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field[] getFieldsCached(Class<?> c) {
        synchronized (FIELD_CACHE) {
            Field[] cached = WRAPPER_FIELDS.get(c.getName());
            if (cached != null) return cached;
            ArrayList<Field> list = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mods)) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive()) continue;
                f.setAccessible(true);
                list.add(f);
            }
            Field[] arr = list.toArray(new Field[0]);
            WRAPPER_FIELDS.put(c.getName(), arr);
            return arr;
        }
    }

    private static boolean isAllowedClass(Class<?> c, ClassLoader cl) {
        String cn = c.getName();
        synchronized (TYPE_OK_CACHE) {
            Boolean ok = TYPE_OK_CACHE.get(cn);
            if (ok != null) return ok;
            ok = false;
            for (String allowed : ALLOWED_TYPES) {
                try {
                    Class<?> ac = Class.forName(allowed, false, cl);
                    if (ac.isAssignableFrom(c)) {
                        ok = true;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            TYPE_OK_CACHE.put(cn, ok);
            return ok;
        }
    }

    private static String tryStr(Object o, String[] getters) {
        Class<?> c = o.getClass();
        for (String g : getters) {
            String key = c.getName() + "#" + g + "()";
            try {
                Method m;
                synchronized (METHOD_CACHE) {
                    m = METHOD_CACHE.get(key);
                    if (m == null) {
                        m = c.getMethod(g);
                        m.setAccessible(true);
                        METHOD_CACHE.put(key, m);
                    }
                }
                Object v = m.invoke(o);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
