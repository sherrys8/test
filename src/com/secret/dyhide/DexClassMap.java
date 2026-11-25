package com.secret.dyhide;

import de.robv.android.xposed.XposedBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 阶段二：dex 扫描结果 → hook 类名的动态映射 + 全程适配日志。
 *
 * 解决的问题：
 *   1) MainHook 的 hook 安装发生在 handleLoadPackage（最早），而 dex 扫描在
 *      MainActivity.onResume（晚）→ 扫描结果必须落盘，下次启动才能用。
 *   2) 任何一次适配（成功/失败/回退/MISS）都必须有日志，供更新抖音后诊断。
 *
 * 三个文件（都在抖音私有目录 dyhide/ 下，属主为 app 用户）：
 *   - dexmap.txt   : 语义名 → 当前真实类名（扫描后覆盖写，启动时读取）
 *   - hooklog.txt  : 本进程每一轮 hook 安装结果（启动时覆盖写）
 *   - adapt_log.txt: 适配历史（追加写，跨版本累积，含扫描 diff）
 *
 * 设计约束（沿用项目惯例）：
 *   - 纯 Java 8，不依赖 java.nio / android.*，避免 D8 匿名内部类问题
 *   - 所有文件操作 try/catch 包住，任何失败都不影响宿主
 */
public final class DexClassMap {

    private static final String TAG = "XProHideContacts";
    private static final String DIR = "/data/user/0/com.ss.android.ugc.aweme/dyhide";
    private static final String MAP_FILE = DIR + "/dexmap.txt";
    private static final String HOOK_FILE = DIR + "/hooklog.txt";
    private static final String HIST_FILE = DIR + "/adapt_log.txt";

    /** 需要动态化的语义名（与 DexFeatureScanner 的 feature.key 一致）。 */
    public static final String[] KEYS = {
            "profile_tab_manager",
            "im_search_source",
            "load_state",
            "user_comparable",
            "search_result_model"
    };
    /** 对应的中文说明，供日志与弹窗展示。 */
    public static final String[] LABELS = {
            "个人页标签管理",
            "IM 搜索源",
            "加载状态模型",
            "用户排序比较器",
            "搜索结果模型"
    };

    private static final Map<String, String> MAP = new LinkedHashMap<String, String>();
    private static final java.util.List<String> HOOK_LINES = new java.util.ArrayList<String>();
    private static volatile String mapMeta = "";
    private static volatile boolean loaded = false;

    private DexClassMap() {}

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    // ------------------------------------------------------------------
    // 映射读取 / 查询（MainHook 启动时调用）
    // ------------------------------------------------------------------

    /** 启动时读取 dexmap.txt；失败则映射为空，MainHook 全部走硬编码兜底。 */
    public static void load() {
        synchronized (MAP) {
            if (loaded) return;
            loaded = true;
            MAP.clear();
            BufferedReader r = null;
            try {
                File f = new File(MAP_FILE);
                if (!f.isFile()) {
                    XposedBridge.log(TAG + ": dexmap not found, use hardcoded fallback");
                    return;
                }
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                int n = 0;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        if (t.startsWith("# host=")) mapMeta = t.substring(7).trim();
                        continue;
                    }
                    int eq = t.indexOf('=');
                    if (eq <= 0) continue;
                    String k = t.substring(0, eq).trim();
                    String v = t.substring(eq + 1).trim();
                    if (k.length() > 0 && v.length() > 0) {
                        MAP.put(k, v);
                        n++;
                    }
                }
                XposedBridge.log(TAG + ": dexmap loaded " + n + " class mapping(s) ["
                        + mapMeta + "]");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": dexmap load failed " + t.getClass().getSimpleName());
            } finally {
                if (r != null) try { r.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 查映射：命中返回真实类名，否则返回硬编码兜底。 */
    public static String resolve(String key, String fallback) {
        if (key != null) {
            synchronized (MAP) {
                String v = MAP.get(key);
                if (v != null && v.length() > 0) return v;
            }
        }
        return fallback;
    }

    /** 该 key 是否来自动态映射（用于日志标注来源）。 */
    public static boolean isDynamic(String key) {
        if (key == null) return false;
        synchronized (MAP) {
            return MAP.containsKey(key);
        }
    }

    /** 当前映射元信息（宿主版本），供弹窗展示。 */
    public static String mapMeta() { return mapMeta; }

    // ------------------------------------------------------------------
    // hook 安装结果记录（MainHook 调用）
    // ------------------------------------------------------------------

    /**
     * 记录一个 hook 点的安装结果。
     * @param key      语义名（动态点用 feature key；静态点传 "static:xxx"）
     * @param label    中文说明
     * @param fallback 硬编码类名（兜底值）
     * @param used     实际使用的类名
     * @param ok       是否安装成功
     * @param detail   方法签名等补充信息
     */
    public static void recordHook(String key, String label, String fallback,
                                  String used, boolean ok, String detail) {
        String src = isDynamic(key) ? "DEXMAP" : "HARDCODE";
        String line = "[" + (ok ? " OK " : "FAIL") + "] " + now()
                + " | " + (label == null ? key : label)
                + " | key=" + key
                + " fallback=" + fallback
                + " used=" + used
                + " src=" + src
                + (detail == null || detail.length() == 0 ? "" : " | " + detail);
        synchronized (HOOK_LINES) {
            HOOK_LINES.add(line);
        }
        XposedBridge.log(TAG + ": [adapt] " + line);
    }

    /** handleLoadPackage 末尾调用：把本轮 hook 结果落盘并追加到历史。 */
    public static void flushHooks(String hostVer) {
        StringBuilder sb = new StringBuilder();
        sb.append("# dyhide hook install log\n");
        sb.append("# host=").append(hostVer).append("\n");
        sb.append("# time=").append(now()).append("\n");
        int ok = 0, fail = 0;
        synchronized (HOOK_LINES) {
            for (String line : HOOK_LINES) {
                sb.append(line).append('\n');
                if (line.startsWith("[ OK ]")) ok++;
                else fail++;
            }
        }
        writeFile(HOOK_FILE, sb.toString());

        // 历史日志追加一段本轮汇总
        StringBuilder h = new StringBuilder();
        h.append("\n=== ").append(now()).append(" | ").append(hostVer).append(" | HOOK ===\n");
        h.append("hook install: ").append(ok).append(" ok, ").append(fail).append(" fail\n");
        synchronized (HOOK_LINES) {
            for (String line : HOOK_LINES) h.append(line).append('\n');
        }
        appendFile(HIST_FILE, h.toString());
        XposedBridge.log(TAG + ": hook log flushed " + ok + "/" + (ok + fail));
    }

    // ------------------------------------------------------------------
    // 扫描结果落盘（CompatCheck 调用）
    // ------------------------------------------------------------------

    /**
     * 扫描完成后：写 dexmap.txt，并把与旧映射的 diff 记入历史日志。
     * @param hits    扫描结果
     * @param hostVer 宿主版本字符串
     * @return diff 摘要（供弹窗展示）
     */
    public static String saveHits(List<DexFeatureScanner.Hit> hits, String hostVer) {
        StringBuilder diff = new StringBuilder();
        StringBuilder map = new StringBuilder();
        map.append("# dyhide dynamic class map (auto generated)\n");
        map.append("# host=").append(hostVer).append("\n");
        map.append("# time=").append(now()).append("\n");

        int ok = 0;
        if (hits != null) {
            for (DexFeatureScanner.Hit h : hits) {
                if (!h.ok || h.javaName == null || h.javaName.length() == 0) continue;
                map.append(h.key).append('=').append(h.javaName).append('\n');
                ok++;
                String old = MAP.get(h.key);
                if (old == null) {
                    diff.append("  + ").append(h.key).append(" = ").append(h.javaName).append(" (新增)\n");
                } else if (!old.equals(h.javaName)) {
                    diff.append("  ~ ").append(h.key).append(": ")
                            .append(old).append(" -> ").append(h.javaName).append(" (变化)\n");
                }
            }
        }
        writeFile(MAP_FILE, map.toString());

        // 更新内存映射，让同进程后续读取一致
        synchronized (MAP) {
            MAP.clear();
            if (hits != null) {
                for (DexFeatureScanner.Hit h : hits) {
                    if (h.ok && h.javaName != null && h.javaName.length() > 0) {
                        MAP.put(h.key, h.javaName);
                    }
                }
            }
            mapMeta = hostVer;
        }

        StringBuilder hist = new StringBuilder();
        hist.append("\n=== ").append(now()).append(" | ").append(hostVer).append(" | SCAN ===\n");
        hist.append("dex feature scan: ").append(ok).append(" mapping(s) saved\n");
        if (hits != null) {
            for (DexFeatureScanner.Hit h : hits) {
                hist.append(h.ok ? "  [OK] " : "  [MISS] ").append(h.key).append(" -> ")
                        .append(h.ok ? h.javaName : "未找到").append('\n');
            }
        }
        hist.append(diff);
        appendFile(HIST_FILE, hist.toString());
        XposedBridge.log(TAG + ": dexmap saved " + ok + " mapping(s) [" + hostVer + "]");

        return diff.length() == 0 ? "类名无变化" : diff.toString();
    }

    /** 扫描失败也要留痕。 */
    public static void recordScanError(String hostVer, String error) {
        StringBuilder hist = new StringBuilder();
        hist.append("\n=== ").append(now()).append(" | ").append(hostVer).append(" | SCAN ===\n");
        hist.append("dex feature scan FAILED: ").append(error).append('\n');
        appendFile(HIST_FILE, hist.toString());
        XposedBridge.log(TAG + ": [adapt] scan failed " + error);
    }

    // ------------------------------------------------------------------
    // 日志读取 / 导出（CompatCheck 调用）
    // ------------------------------------------------------------------

    /** 读取本轮 hook 日志，供弹窗展示。 */
    public static String readHookLog() { return readFile(HOOK_FILE); }

    /** 读取适配历史，供导出。 */
    public static String readHistory() { return readFile(HIST_FILE); }

    /** 导出用的完整适配段落。 */
    public static String formatForExport() {
        StringBuilder sb = new StringBuilder();
        sb.append("---- 动态类名映射 (dexmap) ----\n");
        sb.append(readFile(MAP_FILE)).append('\n');
        sb.append("---- 本轮 hook 安装日志 ----\n");
        sb.append(readHookLog()).append('\n');
        sb.append("---- 适配历史 ----\n");
        sb.append(readHistory()).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 文件工具
    // ------------------------------------------------------------------

    private static void writeFile(String path, String content) {
        FileOutputStream out = null;
        try {
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            out = new FileOutputStream(path, false);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dexmap write failed " + t.getClass().getSimpleName());
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static void appendFile(String path, String content) {
        FileOutputStream out = null;
        try {
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            out = new FileOutputStream(path, true);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": adapt log append failed " + t.getClass().getSimpleName());
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readFile(String path) {
        BufferedReader r = null;
        StringBuilder sb = new StringBuilder();
        try {
            File f = new File(path);
            if (!f.isFile()) return "(无记录)";
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Throwable t) {
            return "(读取失败: " + t.getClass().getSimpleName() + ")";
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
        return sb.toString();
    }
}
