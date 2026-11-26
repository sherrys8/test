package com.secret.dyhide;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.StringReference;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * dex 特征扫描器（基于 dexlib2，纯 Java）。
 *
 * 抖音版本更新后混淆类名/方法名会变，但方法体里的字符串常量、字段引用、方法签名
 * 组合往往稳定。本扫描器直接解析宿主 APK 的 classes*.dex，按特征重新定位 hook 目标，
 * 输出「语义名 → 当前真实类/方法」映射，供 CompatCheck 展示与 MainHook 取用。
 *
 * 支持三类特征：
 *   1) byString    —— 方法体内引用了指定字符串常量
 *   2) byFieldType —— 方法体内引用了指定类型的字段（如 User.profileTabInfoStruct）
 *   3) byMethods   —— 某个类同时具备指定的一组方法签名
 *
 * 设计约束：
 *   - dexlib2 为成熟库（smali/baksmali 同款），不依赖 java.nio / android.*，Android 可跑
 *   - 全部扫描包在 try/catch 内，任何异常不影响宿主
 *   - 不用匿名内部类（D8 对深层匿名类有 NPE 历史问题）
 */
public final class DexFeatureScanner {

    private DexFeatureScanner() {}

    // ------------------------------------------------------------------
    // 特征 / 结果数据结构（接口稳定，供 CompatCheck / MainHook 使用）
    // ------------------------------------------------------------------

    public static final class MethodSig {
        public final String name;
        public final int paramCount;      // -1 = 不限
        public final String returnType;   // null = 不限
        public MethodSig(String name, int paramCount, String returnType) {
            this.name = name;
            this.paramCount = paramCount;
            this.returnType = returnType;
        }
    }

    public static final class Feature {
        public final String key;
        public final String note;
        public final String stringNeedle;
        public final boolean exact;
        public final String fieldType;    // byFieldType：字段所属类型描述符
        public final String fieldName;    // byFieldType：字段名
        public final MethodSig[] sigs;

        private Feature(String key, String note, String needle, boolean exact,
                        String fieldType, String fieldName, MethodSig[] sigs) {
            this.key = key;
            this.note = note;
            this.stringNeedle = needle;
            this.exact = exact;
            this.fieldType = fieldType;
            this.fieldName = fieldName;
            this.sigs = sigs;
        }
        public static Feature byString(String key, String note, String needle, boolean exact) {
            return new Feature(key, note, needle, exact, null, null, null);
        }
        public static Feature byFieldType(String key, String note, String fieldType, String fieldName) {
            return new Feature(key, note, null, false, fieldType, fieldName, null);
        }
        public static Feature byMethods(String key, String note, MethodSig... sigs) {
            return new Feature(key, note, null, false, null, null, sigs);
        }
        public boolean isString() { return stringNeedle != null; }
        public boolean isField() { return fieldType != null; }
    }

    public static final class Hit {
        public final String key;
        public final String note;
        public String descriptor = "";
        public String javaName = "";
        public String method = "";
        public String evidence = "";
        public boolean ok = false;
        Hit(String key, String note) { this.key = key; this.note = note; }
    }

    /** 一个 dex 的轻量封装： dexlib2 的 DexBackedDexFile + 预展开的类列表。 */
    public static final class DexSource {
        final DexBackedDexFile dex;
        final List<? extends ClassDef> classes;
        DexSource(DexBackedDexFile dex) {
            this.dex = dex;
            this.classes = immutableClasses(dex);
        }
        private static List<ClassDef> immutableClasses(DexBackedDexFile dex) {
            List<ClassDef> list = new ArrayList<ClassDef>();
            try {
                for (ClassDef cd : dex.getClasses()) list.add(cd);
            } catch (Throwable t) {
            }
            return list;
        }
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /** 从 APK 里加载所有 dex。 */
    public static List<DexSource> loadDexFiles(String apkPath) {
        List<DexSource> out = new ArrayList<DexSource>();
        try {
            File apk = new File(apkPath);
            if (!apk.isFile()) return out;
            Opcodes opcodes = Opcodes.forApi(34);
            MultiDexContainer<? extends DexBackedDexFile> container =
                    DexFileFactory.loadDexContainer(apk, opcodes);
            if (container == null) return out;
            for (String entry : container.getDexEntryNames()) {
                try {
                    DexBackedDexFile dex = container.getEntry(entry).getDexFile();
                    if (dex != null) out.add(new DexSource(dex));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    public static List<Hit> scan(List<DexSource> dexes, List<Feature> features) {
        List<Hit> results = new ArrayList<Hit>();
        for (Feature f : features) {
            Hit hit = new Hit(f.key, f.note);
            try {
                if (f.isString()) matchString(dexes, f, hit);
                else if (f.isField()) matchField(dexes, f, hit);
                else matchMethods(dexes, f, hit);
            } catch (Throwable t) {
                hit.evidence = "scan error: " + t.getClass().getSimpleName();
            }
            results.add(hit);
        }
        return results;
    }

    private static void matchString(List<DexSource> dexes, Feature f, Hit hit) {
        for (DexSource ds : dexes) {
            for (ClassDef cd : ds.classes) {
                for (Method m : cd.getMethods()) {
                    if (m.getImplementation() == null) continue;
                    for (Instruction ins : m.getImplementation().getInstructions()) {
                        if (!(ins instanceof ReferenceInstruction)) continue;
                        Object ref = ((ReferenceInstruction) ins).getReference();
                        if (!(ref instanceof StringReference)) continue;
                        String s = ((StringReference) ref).getString();
                        if (s == null) continue;
                        boolean found = f.exact ? f.stringNeedle.equals(s) : s.indexOf(f.stringNeedle) >= 0;
                        if (found) {
                            fillHit(hit, cd, m);
                            hit.evidence = "string:\"" + f.stringNeedle + "\" in " + m.getName();
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void matchField(List<DexSource> dexes, Feature f, Hit hit) {
        for (DexSource ds : dexes) {
            for (ClassDef cd : ds.classes) {
                for (Method m : cd.getMethods()) {
                    if (m.getImplementation() == null) continue;
                    for (Instruction ins : m.getImplementation().getInstructions()) {
                        if (!(ins instanceof ReferenceInstruction)) continue;
                        Object ref = ((ReferenceInstruction) ins).getReference();
                        if (!(ref instanceof FieldReference)) continue;
                        FieldReference fr = (FieldReference) ref;
                        if (!f.fieldType.equals(fr.getDefiningClass())) continue;
                        if (f.fieldName != null && !f.fieldName.equals(fr.getName())) continue;
                        fillHit(hit, cd, m);
                        hit.evidence = "field:" + fr.getDefiningClass() + "->" + fr.getName();
                        return;
                    }
                }
            }
        }
    }

    private static void matchMethods(List<DexSource> dexes, Feature f, Hit hit) {
        for (DexSource ds : dexes) {
            for (ClassDef cd : ds.classes) {
                List<Method> methods = new ArrayList<Method>();
                for (Method mm : cd.getMethods()) methods.add(mm);
                if (methods.size() < f.sigs.length) continue;
                boolean all = true;
                for (int s = 0; s < f.sigs.length && all; s++) {
                    MethodSig want = f.sigs[s];
                    boolean one = false;
                    for (Method m : methods) {
                        if (!want.name.equals(m.getName())) continue;
                        List<? extends CharSequence> params = m.getParameterTypes();
                        if (want.paramCount >= 0 && want.paramCount != params.size()) continue;
                        if (want.returnType != null && !want.returnType.equals(m.getReturnType())) continue;
                        one = true;
                        break;
                    }
                    all = one;
                }
                if (all) {
                    fillHit(hit, cd, null);
                    StringBuilder sb = new StringBuilder();
                    for (int s = 0; s < f.sigs.length; s++) {
                        if (s > 0) sb.append(", ");
                        sb.append(f.sigs[s].name).append('/').append(f.sigs[s].paramCount);
                    }
                    hit.evidence = "methods:[" + sb + "]";
                    return;
                }
            }
        }
    }

    private static void fillHit(Hit hit, ClassDef cd, Method m) {
        hit.ok = true;
        hit.descriptor = cd.getType();
        hit.javaName = toJavaName(hit.descriptor);
        if (m != null) hit.method = m.getName();
    }

    static String toJavaName(String desc) {
        if (desc == null || desc.length() < 3) return desc;
        if (desc.charAt(0) != 'L') return desc;
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    /** 默认规则表：语义名 → 稳定特征。 */
    public static List<Feature> defaultFeatures() {
        List<Feature> out = new ArrayList<Feature>();
        // 41.3.0: X.0wC0；旧规则 "ProfileTabManager" 会误命中 OldCollectTabFragment，
        // 改用该管理器独占的 Keva key 字符串（版本更新后若 key 变化需更新此规则）。
        out.add(Feature.byString("profile_tab_manager", "个人主页 Tab 管理器",
                "profile_tab_entered_set", true));
        out.add(Feature.byString("stateflow_impl", "KMP StateFlow 实现类",
                "MutableStateFlow.resetReplayCache is not supported", true));
        out.add(Feature.byString("im_search_source", "IM 搜索源头",
                "start search key:", false));
        out.add(Feature.byString("load_state", "加载状态模型",
                "LOAD_SUCCESS_AND_OVER", true));
        out.add(Feature.byMethods("user_comparable", "用户排序比较器 41.3.0=X.06CG",
                new MethodSig("user", 0, "Lcom/ss/android/ugc/aweme/profile/model/User;"),
                new MethodSig("LIZ", 1, "Z"),
                new MethodSig("LIZIZ", 1, "Z")));
        // 41.3.0: X.1P7u；旧规则 LLFFF() 返回类型改为 String 导致 MISS，
        // 改用 isHasMore + setHasMore + LJZ():List 三签名组合精确定位列表模型。
        out.add(Feature.byMethods("search_result_model", "搜索结果模型 41.3.0=X.1P7u",
                new MethodSig("isHasMore", 0, "Z"),
                new MethodSig("setHasMore", 1, "V"),
                new MethodSig("LJZ", 0, "Ljava/util/List;")));
        return out;
    }

    public static String format(List<Hit> hits) {
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            sb.append(h.ok ? "OK  " : "MISS").append(' ').append(h.key);
            if (h.ok) sb.append(" -> ").append(h.javaName).append(" (").append(h.evidence).append(')');
            else sb.append(" (").append(h.note).append(')');
            sb.append('\n');
        }
        return sb.toString();
    }
}
