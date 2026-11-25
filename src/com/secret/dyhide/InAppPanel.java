package com.secret.dyhide;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抖音内设置面板（v8.0 美化版）：输入 #dy 触发。
 * 「隐藏面」分组包含全部九项功能，每项带小字介绍。
 * 配置写回 xpro_hide.txt 并即时生效。
 */
public class InAppPanel {

    /** 主面板对话框弱引用，供「重新适配检测」关闭面板用。 */
    private static java.lang.ref.WeakReference<AlertDialog> PANEL_DLG =
            new java.lang.ref.WeakReference<AlertDialog>(null);

    /**
     * 功能项定义：key → 显示名 → 小字介绍 → 是否归入 features 集合。
     * feature=1 走 saveConfig 的 features 集合；其余为独立布尔开关。
     */
    private static final String[][] FACE_ITEMS = {
            {"session", "隐藏消息列表会话",
                    "隐藏消息页会话列表中的指定联系人", "1"},
            {"topbar", "隐藏消息页顶部头像条",
                    "将消息页顶部「在线好友」头像栏整条隐藏", "0"},
            {"interact", "隐藏互动消息（点赞/来访等）",
                    "隐藏互动消息列表中的指定联系人", "0"},
            {"search", "隐藏搜索 / 更多聊天",
                    "隐藏 IM 搜索结果与「更多聊天」中的指定联系人", "1"},
            {"blockEnter", "禁止进入聊天页 / 主页",
                    "点击隐藏联系人时拦截其聊天页与个人主页", "0"},
            {"share", "隐藏视频 / 直播分享面板 / 头像条",
                    "隐藏视频、直播的分享面板与联系人头像条中的指定联系人", "1"},
            {"invite", "隐藏一起看邀请面板",
                    "隐藏「一起看」邀请面板中的指定联系人", "1"},
            {"relation", "隐藏关注 / 粉丝 / 互关 / 朋友列表",
                    "隐藏四个关系列表及其搜索结果中的指定联系人", "1"},
            {"visitor", "隐藏主页访客记录",
                    "隐藏个人主页顶部「访客」入口中的指定联系人", "0"},
            {"profileEntry", "隐藏个人主页卡片入口",
                    "隐藏「我」页中间那排入口：团购带货/我的订单/观看历史/我的钱包/全部功能", "0"},
            {"profileTab", "隐藏个人主页标签",
                    "隐藏「我」页标签栏：作品/日常/收藏/喜欢/去过", "0"},
            {"cleanStorage", "隐藏聊天记录清理页",
                    "隐藏「清理缓存 → 聊天记录」空间管理页中的指定联系人", "0"},
    };

    /** 分组：组名 → 组内 key（逗号分隔）；「侧边栏」组为两个自定义删除块，不走 faceItem。 */
    private static final String[][] FACE_GROUPS = {
            {"恢复", ""},
            {"消息列表", "session,topbar,interact,search,blockEnter"},
            {"视频", "share,invite"},
            {"主页", "relation,visitor,cleanStorage,profileEntry,profileTab"},
            {"侧边栏", ""},
    };

    /** 分组折叠状态（本次面板会话内有效）：默认全部收起，点击展开后移出集合 */
    private static final Set<String> COLLAPSED_GROUPS = new HashSet<String>();
    static {
        for (String[] g : FACE_GROUPS) COLLAPSED_GROUPS.add(g[0]);
    }

    /** 按 key 取功能项定义 */
    private static String[] itemOf(String key) {
        for (String[] it : FACE_ITEMS) {
            if (it[0].equals(key)) return it;
        }
        return null;
    }

    // ---- 樱粉紫配色 ----
    private static final int C_CARD     = 0xFFFFFFFF;
    private static final int C_TITLE    = 0xFF3A2A35;
    private static final int C_SUB      = 0xFF967E8E;
    private static final int C_PINK     = 0xFFFF9EC0;
    private static final int C_PURPLE   = 0xFFB896E8;
    private static final int C_ACCENT   = 0xFFD96C9F;

    public static void show(final Context ctx) {
        try {
            float d = ctx.getResources().getDisplayMetrics().density;

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding((int) (14 * d), dp(d, 4), (int) (14 * d), 0);
            root.setBackgroundColor(0xFFFAF5F8);

            // ===== 标题栏：图标 + dyhide（图标获取失败时不留空位） =====
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0, dp(d, 10), 0, dp(d, 10));

            android.graphics.drawable.Drawable ic = loadModuleIcon(ctx);
            if (ic != null) {
                ImageView icon = new ImageView(ctx);
                icon.setImageDrawable(ic);
                LinearLayout.LayoutParams ilp =
                        new LinearLayout.LayoutParams(dp(d, 30), dp(d, 30));
                ilp.rightMargin = dp(d, 9);
                head.addView(icon, ilp);
            }

            TextView title = new TextView(ctx);
            title.setText("dyhide");
            title.setTextSize(17);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(C_TITLE);
            head.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(head);

            // ===== 总开关卡片 =====
            final Switch master = new Switch(ctx);
            master.setText("启用隐藏（总开关）");
            master.setTextSize(15);
            master.setTypeface(Typeface.DEFAULT_BOLD);
            master.setTextColor(C_TITLE);
            master.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12));
            master.setChecked(MainHook.isEnabled());
            tintSwitch(master);
            root.addView(cardOf(ctx, d, master));

            // ===== 统计信息（提前创建，供恢复组里的「选择隐藏联系人」按钮刷新） =====
            final TextView count = new TextView(ctx);
            count.setTextSize(12);
            count.setTextColor(C_ACCENT);
            count.setTypeface(Typeface.DEFAULT_BOLD);
            count.setPadding(dp(d, 6), dp(d, 12), 0, 0);
            count.setText("已隐藏 " + MainHook.hiddenCount() + " 人 · 好友池 "
                    + MainHook.getFriends().size() + " 人");

            // ===== 功能分组 =====
            final Map<String, Switch> boxes = new LinkedHashMap<>();
            for (int g = 0; g < FACE_GROUPS.length; g++) {
                String groupName = FACE_GROUPS[g][0];
                final TextView gHead = groupLabel(ctx, d, groupName);
                root.addView(gHead);
                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);

                if ("恢复".equals(groupName)) {
                    card.addView(restoreBlock(ctx, d, boxes, master, count));
                } else if ("侧边栏".equals(groupName)) {
                    card.addView(sidebarBlock(ctx, d,
                            "自定义删除左滑侧滑栏入口",
                            "按名单删除左滑侧滑栏中的入口项",
                            MainHook.isSidebarHideEnabled(),
                            new SidebarSwitchListener(),
                            MainHook.getSidebarHideNameList(),
                            "编辑左滑侧滑栏名单…", 0));
                    card.addView(divider(ctx, d, dp(d, 14)));
                    card.addView(sidebarBlock(ctx, d,
                            "自定义删除右滑侧滑栏入口",
                            "按名单删除「我」页右滑侧滑栏中的入口项",
                            MainHook.isSidebarHideRightEnabled(),
                            new SidebarRightSwitchListener(),
                            MainHook.getSidebarHideRightNameList(),
                            "编辑右滑侧滑栏名单…", 1));
                } else {
                    String[] keys = FACE_GROUPS[g][1].split(",");
                    int total = 0;
                    for (String k : keys) {
                        if (itemOf(k.trim()) != null) total++;
                    }
                    int idx = 0;
                    for (String k : keys) {
                        String[] item = itemOf(k.trim());
                        if (item == null) continue;
                        if ("profileEntry".equals(k.trim())) {
                            // 「总开关 + 逐个隐藏」合并为一块（布局同侧边栏）
                            card.addView(profileEntryBlock(ctx, d, boxes, master));
                        } else if ("profileTab".equals(k.trim())) {
                            // 个人主页标签逐个隐藏（作品/日常/收藏/喜欢/去过）
                            card.addView(profileTabBlock(ctx, d, boxes, master));
                        } else {
                            card.addView(faceItem(ctx, d, item, boxes, master));
                        }
                        if (++idx < total) card.addView(divider(ctx, d, dp(d, 14)));
                    }

                }
                final LinearLayout wrapped = cardOf(ctx, d, card);
                root.addView(wrapped);
                attachCollapse(ctx, d, gHead, wrapped, groupName);
            }

            // ===== 统计信息 =====
            root.addView(count, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // ===== 重新适配检测（与恢复组的「选择隐藏联系人」互换位置与描述） =====
            TextView recheckHint = new TextView(ctx);
            recheckHint.setText("抖音版本更新后可重新解析全部适配项");
            recheckHint.setTextSize(11);
            recheckHint.setTextColor(C_SUB);
            recheckHint.setPadding(dp(d, 6), dp(d, 6), dp(d, 6), 0);
            root.addView(recheckHint);

            TextView recheckMain = new TextView(ctx);
            recheckMain.setText("重新适配检测");
            recheckMain.setTextSize(15);
            recheckMain.setTypeface(Typeface.DEFAULT_BOLD);
            recheckMain.setTextColor(0xFFFFFFFF);
            recheckMain.setGravity(Gravity.CENTER);
            GradientDrawable rg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{C_PINK, C_PURPLE});
            rg.setCornerRadius(dp(d, 14));
            recheckMain.setBackgroundDrawable(rg);
            recheckMain.setPadding(0, dp(d, 12), 0, dp(d, 12));
            LinearLayout.LayoutParams rmlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rmlp.topMargin = dp(d, 4);
            rmlp.bottomMargin = dp(d, 4);
            root.addView(recheckMain, rmlp);
            recheckMain.setOnClickListener(new RecheckClick());

            ScrollView sv = new ScrollView(ctx);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(root);

            final AlertDialog dlg = new AlertDialog.Builder(ctx)
                    .setTitle("")
                    .setView(sv)
                    .setPositiveButton("保存", null)
                    .setNegativeButton("关闭", null)
                    .create();
            PANEL_DLG = new java.lang.ref.WeakReference<AlertDialog>(dlg);

            try {
                if (dlg.getWindow() != null) {
                    GradientDrawable winBg = new GradientDrawable();
                    winBg.setColor(0xFFFAF5F8);
                    winBg.setCornerRadius(dp(d, 18));
                    dlg.getWindow().setBackgroundDrawable(winBg);
                }
            } catch (Throwable ignored) {
            }
            dlg.setOnShowListener(new DialogShowListener(dlg, master, boxes));
            android.util.Log.i("XProHideContacts", "panel built boxes=" + boxes.size()
                    + " id=" + System.identityHashCode(boxes)
                    + " sw=" + System.identityHashCode(boxes.get("interact")));

            dlg.show();

            // 动态适配屏幕：高 70%、宽 88%（左右留间隔），任意手机一致
            try {
                if (dlg.getWindow() != null) {
                    android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    int winW = (int) (dm.widthPixels * 0.88f);
                    int winH = (int) (dm.heightPixels * 0.70f);
                    dlg.getWindow().setLayout(winW, winH);
                }
            } catch (Throwable ignored) {
            }

            try {
                android.widget.Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                android.widget.Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (pos != null) pos.setTextColor(C_ACCENT);
                if (neg != null) neg.setTextColor(C_SUB);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            String stack = android.util.Log.getStackTraceString(t);
            android.util.Log.e("XProHideContacts", "panel build FAILED: " + stack);
            Toast.makeText(ctx, "打开设置失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 右滑侧边栏 · 观看历史开关（即时保存） */
    private static class MenuWatchSwitchListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                MainHook.setMenuWatchHistoryHidden(isChecked);
                Toast.makeText(buttonView.getContext(),
                        "已保存，重启抖音后生效", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 需要重启才生效的开关提示 */
    private static class RestartTipListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                Toast.makeText(buttonView.getContext(),
                        "该项修改后需重启抖音才生效", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 与 faceItem 同样式的一行开关（用于侧边栏分组内） */
    private static View entryRow(Context ctx, float d, String title, String desc,
                                 boolean checked,
                                 android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout unit = new LinearLayout(ctx);
        unit.setOrientation(LinearLayout.VERTICAL);
        unit.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(C_TITLE);
        label.setPadding(0, 0, dp(d, 8), 0);
        top.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        tintSwitch(sw);
        if (l != null) sw.setOnCheckedChangeListener(l);
        top.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        unit.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView dv = new TextView(ctx);
        dv.setText(desc);
        dv.setTextSize(11);
        dv.setTextColor(C_SUB);
        dv.setPadding(0, dp(d, 2), 0, 0);
        unit.addView(dv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return unit;
    }

    /** 「我」页入口条自定义删除开关 */
    private static class ProfileEntrySwitchListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                MainHook.setProfileEntryEnabled(isChecked);
                Toast.makeText(buttonView.getContext(),
                        "已保存，重新进入「我」页后生效", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 右滑侧边栏自定义删除开关 */
    private static class SidebarRightSwitchListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                MainHook.setSidebarHideRightEnabled(isChecked);
                Toast.makeText(buttonView.getContext(),
                        "已保存，重启抖音后生效", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 侧边栏自定义删除总开关 */
    private static class SidebarSwitchListener implements android.widget.CompoundButton.OnCheckedChangeListener {
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                MainHook.setSidebarHideEnabled(isChecked);
                Toast.makeText(buttonView.getContext(),
                        "已保存，重启抖音后生效", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 打开删除名单编辑弹窗 */
    private static class EditNamesListener implements View.OnClickListener {
        private final TextView summary;
        private final int kind;

        EditNamesListener(TextView summary, int kind) {
            this.summary = summary;
            this.kind = kind;
        }

        @Override
        public void onClick(View v) {
            new NameEditor(v.getContext(), summary, kind).show();
        }
    }

    /** 侧边栏自定义删除区块：标题 + 开关 + 名单摘要 + 编辑按钮 */
    private static View sidebarBlock(Context ctx, float d, String title, String desc,
                                     boolean checked,
                                     android.widget.CompoundButton.OnCheckedChangeListener l,
                                     java.util.List<String> names, String editLabel,
                                     int kind) {
        LinearLayout unit = new LinearLayout(ctx);
        unit.setOrientation(LinearLayout.VERTICAL);
        unit.setPadding(dp(d, 14), dp(d, 12), dp(d, 14), dp(d, 12));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(C_TITLE);
        label.setPadding(0, 0, dp(d, 8), 0);
        top.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        tintSwitch(sw);
        if (l != null) sw.setOnCheckedChangeListener(l);
        top.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        unit.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView dv = new TextView(ctx);
        dv.setText(desc);
        dv.setTextSize(11);
        dv.setTextColor(C_SUB);
        dv.setPadding(0, dp(d, 3), 0, 0);
        unit.addView(dv);

        TextView summary = new TextView(ctx);
        summary.setTextSize(11);
        summary.setTextColor(0xFF967E8E);
        summary.setPadding(0, dp(d, 7), 0, 0);
        summary.setText(namesSummary(names));
        unit.addView(summary);

        TextView edit = new TextView(ctx);
        edit.setText(editLabel);
        edit.setTextSize(13);
        edit.setTypeface(Typeface.DEFAULT_BOLD);
        edit.setTextColor(C_ACCENT);
        edit.setGravity(Gravity.CENTER);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0x1AD96C9F);
        eg.setCornerRadius(dp(d, 12));
        edit.setBackgroundDrawable(eg);
        edit.setPadding(0, dp(d, 10), 0, dp(d, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(d, 10);
        unit.addView(edit, elp);
        edit.setOnClickListener(new EditNamesListener(summary, kind));

        return unit;
    }

    /** 「隐藏个人主页卡片入口」+「逐个隐藏」合并块（布局同侧边栏自定义删除块） */
    private static View profileEntryBlock(final Context ctx, float d,
                                          final Map<String, Switch> boxes, final Switch master) {
        LinearLayout unit = new LinearLayout(ctx);
        unit.setOrientation(LinearLayout.VERTICAL);
        unit.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));

        // 第一行：标题 + 总开关（与原 faceItem 一致，纳入 boxes 走保存链路）
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(ctx);
        label.setText("隐藏个人主页卡片入口");
        label.setTextSize(14);
        label.setTextColor(C_TITLE);
        label.setPadding(0, 0, dp(d, 8), 0);
        top.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(ctx);
        sw.setChecked(MainHook.isProfileEntryEnabled());
        tintSwitch(sw);
        // 拨动即提交全部勾选状态，避免只写本项冲掉其它 features
        sw.setOnCheckedChangeListener(new SyncAllListener(ctx, master, boxes));
        top.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boxes.put("profileEntry", sw);
        unit.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第二行：小字介绍（与 name 左对齐）
        TextView desc = new TextView(ctx);
        desc.setText("隐藏「我」页中间那排入口；开启后可在下方逐个勾选，只隐藏选中的入口。");
        desc.setTextSize(11);
        desc.setTextColor(C_SUB);
        desc.setPadding(0, dp(d, 3), 0, 0);
        unit.addView(desc);

        // 第三行：名单摘要
        TextView summary = new TextView(ctx);
        summary.setTextSize(11);
        summary.setTextColor(C_SUB);
        summary.setPadding(0, dp(d, 7), 0, 0);
        summary.setText(profileEntrySummary());
        unit.addView(summary);

        // 第四行：选择入口按钮（与侧边栏「编辑…」按钮同款）
        TextView pick = new TextView(ctx);
        pick.setText("选择要隐藏的主页入口…");
        pick.setTextSize(13);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setTextColor(C_ACCENT);
        pick.setGravity(Gravity.CENTER);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0x1AD96C9F);
        eg.setCornerRadius(dp(d, 12));
        pick.setBackgroundDrawable(eg);
        pick.setPadding(0, dp(d, 10), 0, dp(d, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(d, 10);
        unit.addView(pick, elp);
        pick.setOnClickListener(new ProfileEntryPickListener(ctx, summary));

        return unit;
    }

    /** 「隐藏个人主页标签」块（布局同 profileEntryBlock：标题+开关+介绍+摘要+选择按钮） */
    private static View profileTabBlock(final Context ctx, float d,
                                        final Map<String, Switch> boxes, final Switch master) {
        LinearLayout unit = new LinearLayout(ctx);
        unit.setOrientation(LinearLayout.VERTICAL);
        unit.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(ctx);
        label.setText("隐藏个人主页标签");
        label.setTextSize(14);
        label.setTextColor(C_TITLE);
        label.setPadding(0, 0, dp(d, 8), 0);
        top.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(ctx);
        sw.setChecked(MainHook.isProfileTabEnabled());
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new SyncAllListener(ctx, master, boxes));
        top.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boxes.put("profileTab", sw);
        unit.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(ctx);
        desc.setText("隐藏「我」页作品/日常/收藏/喜欢/去过标签；开启后可只隐藏勾选的标签。");
        desc.setTextSize(11);
        desc.setTextColor(C_SUB);
        desc.setPadding(0, dp(d, 3), 0, 0);
        unit.addView(desc);

        TextView summary = new TextView(ctx);
        summary.setTextSize(11);
        summary.setTextColor(C_SUB);
        summary.setPadding(0, dp(d, 7), 0, 0);
        summary.setText(profileTabSummary());
        unit.addView(summary);

        TextView pick = new TextView(ctx);
        pick.setText("选择要隐藏的主页标签…");
        pick.setTextSize(13);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setTextColor(C_ACCENT);
        pick.setGravity(Gravity.CENTER);
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0x1AD96C9F);
        eg.setCornerRadius(dp(d, 12));
        pick.setBackgroundDrawable(eg);
        pick.setPadding(0, dp(d, 10), 0, dp(d, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(d, 10);
        unit.addView(pick, elp);
        pick.setOnClickListener(new ProfileTabPickListener(ctx, summary));

        return unit;
    }

    /** 已勾选主页标签的可读摘要 */
    private static String profileTabSummary() {
        java.util.List<String> keys = MainHook.getProfileTabNameList();
        if (keys == null || keys.isEmpty()) return "当前名单：空（打开「我」页识别标签后勾选）";
        StringBuilder sb = new StringBuilder("当前名单：");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append("、");
            String k = keys.get(i);
            String label = MainHook.getDiscoveredProfileTabLabel(k);
            sb.append(label == null || label.isEmpty() ? k : label);
        }
        return sb.toString();
    }

    /** 已勾选主页入口的可读摘要：显示名（标识），空时给出引导 */
    private static String profileEntrySummary() {
        java.util.List<String> keys = MainHook.getProfileEntryNameList();
        if (keys == null || keys.isEmpty()) return "当前名单：空（打开「我」页识别入口后勾选）";
        StringBuilder sb = new StringBuilder("当前名单：");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append("、");
            String k = keys.get(i);
            String label = MainHook.getDiscoveredProfileEntryLabel(k);
            sb.append(label == null || label.isEmpty() ? k : label);
        }
        return sb.toString();
    }

    /** 打开入口勾选弹窗（样式与 NameEditor / showOptionDialog 一致） */
    private static void showProfileEntryPicker(final Context ctx, final TextView summary) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final java.util.List<String> fkeys = MainHook.getDiscoveredProfileEntryKeys();
        final java.util.Set<String> selected = new HashSet<String>(MainHook.getProfileEntryNameList());
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(d, 20), dp(d, 18), dp(d, 20), dp(d, 14));

        // 标题：左侧玫粉竖条 + 文案（与面板其它弹窗一致）
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(ctx);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(C_ACCENT);
        barBg.setCornerRadius(dp(d, 2));
        bar.setBackgroundDrawable(barBg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(d, 4), dp(d, 17));
        barLp.rightMargin = dp(d, 9);
        head.addView(bar, barLp);
        TextView title = new TextView(ctx);
        title.setText("选择要隐藏的主页入口");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TITLE);
        head.addView(title);
        box.addView(head);

        TextView tip = new TextView(ctx);
        tip.setText("勾选的入口将被隐藏，未勾选的保留。首次请先打开「我」页识别入口。");
        tip.setTextSize(11);
        tip.setTextColor(C_SUB);
        tip.setPadding(0, dp(d, 6), 0, dp(d, 10));
        box.addView(tip);

        // 勾选列表（圆角卡片行）
        ScrollView sc = new ScrollView(ctx);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);

        if (fkeys.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("还没有识别到入口。\n请先打开抖音「我」页停留几秒，让模块扫描入口，再回来点此按钮。");
            empty.setTextSize(12);
            empty.setTextColor(0xFFB49CAC);
            empty.setPadding(dp(d, 4), dp(d, 16), dp(d, 4), dp(d, 16));
            listBox.addView(empty);
        } else {
            for (int i = 0; i < fkeys.size(); i++) {
                final String key = fkeys.get(i);
                String label = MainHook.getDiscoveredProfileEntryLabel(key);
                if (label == null || label.isEmpty()) label = key;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable rbg = new GradientDrawable();
                rbg.setColor(0xFFF8EFF4);
                rbg.setCornerRadius(dp(d, 10));
                row.setBackgroundDrawable(rbg);
                row.setPadding(dp(d, 12), dp(d, 7), dp(d, 12), dp(d, 7));

                CheckBox cb = new CheckBox(ctx);
                cb.setText(label + "   (" + key + ")");
                cb.setTextSize(14);
                cb.setTextColor(C_TITLE);
                cb.setChecked(selected.contains(key));
                cb.setOnCheckedChangeListener(new EntryCheckListener(selected, key));
                row.addView(cb, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(d, 7);
                listBox.addView(row, rlp);
            }
        }
        sc.addView(listBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        slp.weight = 1f;
        slp.topMargin = dp(d, 4);
        box.addView(sc, slp);

        // 底部按钮：取消（浅色）+ 保存（渐变，与 NameEditor 一致）
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(d, 12), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(C_SUB);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFFF1E7ED);
        cbg.setCornerRadius(dp(d, 11));
        cancel.setBackgroundDrawable(cbg);
        cancel.setPadding(0, dp(d, 11), 0, dp(d, 11));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(d, 6);
        btns.addView(cancel, clp);
        cancel.setOnClickListener(new EntryPickerCancelListener(holder));

        TextView save = new TextView(ctx);
        save.setText("保存");
        save.setTextSize(14);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(0xFFFFFFFF);
        save.setGravity(Gravity.CENTER);
        GradientDrawable sbg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_PINK, C_PURPLE});
        sbg.setCornerRadius(dp(d, 11));
        save.setBackgroundDrawable(sbg);
        save.setPadding(0, dp(d, 11), 0, dp(d, 11));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        svlp.leftMargin = dp(d, 6);
        btns.addView(save, svlp);
        save.setOnClickListener(new EntryPickerSaveListener(ctx, selected, summary, holder));
        box.addView(btns);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(box).create();
        holder[0] = dlg;
        dlg.show();
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            dlg.getWindow().setLayout((int) (dm.widthPixels * 0.86f),
                    (int) (dm.heightPixels * 0.55f));
            GradientDrawable wbg = new GradientDrawable();
            wbg.setColor(0xFFFFFFFF);
            wbg.setCornerRadius(dp(d, 20));
            dlg.getWindow().setBackgroundDrawable(wbg);
        } catch (Throwable ignored) {
        }
    }

    /** 入口勾选状态监听 */
    private static class EntryCheckListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final Set<String> selected;
        private final String key;
        EntryCheckListener(Set<String> selected, String key) {
            this.selected = selected;
            this.key = key;
        }
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                if (isChecked) selected.add(key);
                else selected.remove(key);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 打开入口勾选弹窗 */
    private static class ProfileEntryPickListener implements View.OnClickListener {
        private final Context ctx;
        private final TextView summary;
        ProfileEntryPickListener(Context ctx, TextView summary) {
            this.ctx = ctx;
            this.summary = summary;
        }
        @Override
        public void onClick(View v) {
            showProfileEntryPicker(ctx, summary);
        }
    }

    /** 关闭入口勾选弹窗 */
    private static class EntryPickerCancelListener implements View.OnClickListener {
        private final AlertDialog[] holder;
        EntryPickerCancelListener(AlertDialog[] holder) {
            this.holder = holder;
        }
        @Override
        public void onClick(View v) {
            try {
                if (holder[0] != null) holder[0].dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 保存入口勾选（保存后即时刷新合并块名单摘要） */
    private static class EntryPickerSaveListener implements View.OnClickListener {
        private final Context ctx;
        private final Set<String> selected;
        private final TextView summary;
        private final AlertDialog[] holder;
        EntryPickerSaveListener(Context ctx, Set<String> selected, TextView summary,
                                AlertDialog[] holder) {
            this.ctx = ctx;
            this.selected = selected;
            this.summary = summary;
            this.holder = holder;
        }
        @Override
        public void onClick(View v) {
            try {
                MainHook.setProfileEntryNames(new ArrayList<String>(selected));
                if (summary != null) summary.setText(profileEntrySummary());
                Toast.makeText(ctx, "已保存 " + selected.size() + " 个入口，重新进入「我」页生效",
                        Toast.LENGTH_SHORT).show();
                if (holder[0] != null) holder[0].dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    // ============ 个人主页标签勾选弹窗 ============
    /** 打开主页标签勾选弹窗（样式与入口弹窗一致） */
    private static void showProfileTabPicker(final Context ctx, final TextView summary) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final java.util.List<String> fkeys = MainHook.getDiscoveredProfileTabKeys();
        final java.util.Set<String> selected = new HashSet<String>(MainHook.getProfileTabNameList());
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(d, 20), dp(d, 18), dp(d, 20), dp(d, 14));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(ctx);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(C_ACCENT);
        barBg.setCornerRadius(dp(d, 2));
        bar.setBackgroundDrawable(barBg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(d, 4), dp(d, 17));
        barLp.rightMargin = dp(d, 9);
        head.addView(bar, barLp);
        TextView title = new TextView(ctx);
        title.setText("选择要隐藏的主页标签");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TITLE);
        head.addView(title);
        box.addView(head);

        TextView tip = new TextView(ctx);
        tip.setText("勾选的标签将被隐藏，未勾选的保留。首次请先打开「我」页识别标签。");
        tip.setTextSize(11);
        tip.setTextColor(C_SUB);
        tip.setPadding(0, dp(d, 6), 0, dp(d, 10));
        box.addView(tip);

        ScrollView sc = new ScrollView(ctx);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout listBox = new LinearLayout(ctx);
        listBox.setOrientation(LinearLayout.VERTICAL);

        if (fkeys.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("还没有识别到标签。\n请先打开抖音「我」页停留几秒，让模块扫描标签，再回来点此按钮。");
            empty.setTextSize(12);
            empty.setTextColor(0xFFB49CAC);
            empty.setPadding(dp(d, 4), dp(d, 16), dp(d, 4), dp(d, 16));
            listBox.addView(empty);
        } else {
            for (int i = 0; i < fkeys.size(); i++) {
                final String key = fkeys.get(i);
                String label = MainHook.getDiscoveredProfileTabLabel(key);
                if (label == null || label.isEmpty()) label = key;
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                GradientDrawable rbg = new GradientDrawable();
                rbg.setColor(0xFFF8EFF4);
                rbg.setCornerRadius(dp(d, 10));
                row.setBackgroundDrawable(rbg);
                row.setPadding(dp(d, 12), dp(d, 7), dp(d, 12), dp(d, 7));
                CheckBox cb = new CheckBox(ctx);
                cb.setText(label + "   (" + key + ")");
                cb.setTextSize(14);
                cb.setTextColor(C_TITLE);
                cb.setChecked(selected.contains(key));
                cb.setOnCheckedChangeListener(new TabCheckListener(selected, key));
                row.addView(cb, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(d, 7);
                listBox.addView(row, rlp);
            }
        }
        sc.addView(listBox);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        slp.weight = 1f;
        slp.topMargin = dp(d, 4);
        box.addView(sc, slp);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(d, 12), 0, 0);
        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(C_SUB);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFFF1E7ED);
        cbg.setCornerRadius(dp(d, 11));
        cancel.setBackgroundDrawable(cbg);
        cancel.setPadding(0, dp(d, 11), 0, dp(d, 11));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(d, 6);
        btns.addView(cancel, clp);
        cancel.setOnClickListener(new TabPickerCancelListener(holder));

        TextView save = new TextView(ctx);
        save.setText("保存");
        save.setTextSize(14);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(0xFFFFFFFF);
        save.setGravity(Gravity.CENTER);
        GradientDrawable sbg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_PINK, C_PURPLE});
        sbg.setCornerRadius(dp(d, 11));
        save.setBackgroundDrawable(sbg);
        save.setPadding(0, dp(d, 11), 0, dp(d, 11));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        svlp.leftMargin = dp(d, 6);
        btns.addView(save, svlp);
        save.setOnClickListener(new TabPickerSaveListener(ctx, selected, summary, holder));
        box.addView(btns);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(box).create();
        holder[0] = dlg;
        dlg.show();
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            dlg.getWindow().setLayout((int) (dm.widthPixels * 0.86f),
                    (int) (dm.heightPixels * 0.55f));
            GradientDrawable wbg = new GradientDrawable();
            wbg.setColor(0xFFFFFFFF);
            wbg.setCornerRadius(dp(d, 20));
            dlg.getWindow().setBackgroundDrawable(wbg);
        } catch (Throwable ignored) {
        }
    }

    /** 打开主页标签勾选弹窗 */
    private static class ProfileTabPickListener implements View.OnClickListener {
        private final Context ctx;
        private final TextView summary;
        ProfileTabPickListener(Context ctx, TextView summary) {
            this.ctx = ctx;
            this.summary = summary;
        }
        @Override
        public void onClick(View v) {
            showProfileTabPicker(ctx, summary);
        }
    }

    /** 标签勾选状态变化 */
    private static class TabCheckListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final Set<String> selected;
        private final String key;
        TabCheckListener(Set<String> selected, String key) {
            this.selected = selected;
            this.key = key;
        }
        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            if (isChecked) selected.add(key);
            else selected.remove(key);
        }
    }

    /** 关闭标签勾选弹窗 */
    private static class TabPickerCancelListener implements View.OnClickListener {
        private final AlertDialog[] holder;
        TabPickerCancelListener(AlertDialog[] holder) { this.holder = holder; }
        @Override
        public void onClick(View v) {
            try { if (holder[0] != null) holder[0].dismiss(); } catch (Throwable ignored) { }
        }
    }

    /** 保存标签勾选 */
    private static class TabPickerSaveListener implements View.OnClickListener {
        private final Context ctx;
        private final Set<String> selected;
        private final TextView summary;
        private final AlertDialog[] holder;
        TabPickerSaveListener(Context ctx, Set<String> selected, TextView summary,
                              AlertDialog[] holder) {
            this.ctx = ctx;
            this.selected = selected;
            this.summary = summary;
            this.holder = holder;
        }
        @Override
        public void onClick(View v) {
            try {
                MainHook.setProfileTabNames(new ArrayList<String>(selected));
                if (summary != null) summary.setText(profileTabSummary());
                Toast.makeText(ctx, "已保存 " + selected.size() + " 个标签，重新进入「我」页生效",
                        Toast.LENGTH_SHORT).show();
                if (holder[0] != null) holder[0].dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 名单摘要文案 */
    private static String namesSummary(java.util.List<String> names) {
        if (names == null || names.isEmpty()) return "当前名单：空";
        StringBuilder sb = new StringBuilder("当前名单：");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    /** 名单编辑器：一行输入 + 添加，下方条目可逐个删除 */
    private static class NameEditor {
        private final Context ctx;
        private final float d;
        private final java.util.List<String> names = new java.util.ArrayList<>();
        private final LinearLayout listBox;
        private final EditText input;
        private final TextView summary;
        private final int kind;
        private AlertDialog dlg;

        NameEditor(Context ctx, TextView summary, int kind) {
            this.ctx = ctx;
            this.d = ctx.getResources().getDisplayMetrics().density;
            this.summary = summary;
            this.kind = kind;
            this.names.addAll(kind == 1
                    ? MainHook.getSidebarHideRightNameList()
                    : (kind == 2 ? MainHook.getProfileEntryNameList()
                    : MainHook.getSidebarHideNameList()));

            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(d, 20), dp(d, 18), dp(d, 20), dp(d, 14));

            // 标题
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            View bar = new View(ctx);
            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(C_ACCENT);
            barBg.setCornerRadius(dp(d, 2));
            bar.setBackgroundDrawable(barBg);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    dp(d, 4), dp(d, 16));
            barLp.rightMargin = dp(d, 8);
            head.addView(bar, barLp);
            TextView title = new TextView(ctx);
            title.setText(kind == 1 ? "右滑侧滑栏删除名单"
                    : (kind == 2 ? "「我」页入口条删除名单" : "左滑侧滑栏删除名单"));
            title.setTextSize(16);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(C_TITLE);
            head.addView(title);
            box.addView(head);

            TextView hint = new TextView(ctx);
            hint.setText("输入入口名称后点「添加」，与侧边栏显示的文字一致即可");
            hint.setTextSize(11);
            hint.setTextColor(C_SUB);
            hint.setPadding(0, dp(d, 6), 0, dp(d, 12));
            box.addView(hint);

            // 输入行
            LinearLayout inputRow = new LinearLayout(ctx);
            inputRow.setOrientation(LinearLayout.HORIZONTAL);
            inputRow.setGravity(Gravity.CENTER_VERTICAL);

            input = new EditText(ctx);
            input.setTextSize(14);
            input.setTextColor(C_TITLE);
            input.setSingleLine(true);
            input.setHint("例如：观看历史");
            input.setHintTextColor(0xFFB49CAC);
            GradientDrawable ibg = new GradientDrawable();
            ibg.setColor(0xFFF8EFF4);
            ibg.setCornerRadius(12 * d);
            input.setBackgroundDrawable(ibg);
            input.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));
            inputRow.addView(input, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView add = new TextView(ctx);
            add.setText("添加");
            add.setTextSize(13);
            add.setTypeface(Typeface.DEFAULT_BOLD);
            add.setTextColor(0xFFFFFFFF);
            add.setGravity(Gravity.CENTER);
            GradientDrawable abg = new GradientDrawable();
            abg.setColor(C_ACCENT);
            abg.setCornerRadius(12 * d);
            add.setBackgroundDrawable(abg);
            add.setPadding(dp(d, 16), dp(d, 10), dp(d, 16), dp(d, 10));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.leftMargin = dp(d, 8);
            inputRow.addView(add, alp);
            add.setOnClickListener(new NameAddListener(this));
            box.addView(inputRow);

            // 条目列表
            ScrollView sc = new ScrollView(ctx);
            sc.setVerticalScrollBarEnabled(false);
            listBox = new LinearLayout(ctx);
            listBox.setOrientation(LinearLayout.VERTICAL);
            listBox.setPadding(0, dp(d, 10), 0, 0);
            sc.addView(listBox);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0);
            slp.weight = 1f;
            slp.topMargin = dp(d, 4);
            box.addView(sc, slp);

            // 底部按钮
            LinearLayout btns = new LinearLayout(ctx);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, dp(d, 12), 0, 0);

            TextView cancel = new TextView(ctx);
            cancel.setText("取消");
            cancel.setTextSize(14);
            cancel.setTextColor(C_SUB);
            cancel.setGravity(Gravity.CENTER);
            GradientDrawable cbg = new GradientDrawable();
            cbg.setColor(0xFFF1E7ED);
            cbg.setCornerRadius(12 * d);
            cancel.setBackgroundDrawable(cbg);
            cancel.setPadding(0, dp(d, 11), 0, dp(d, 11));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = dp(d, 6);
            btns.addView(cancel, clp);
            cancel.setOnClickListener(new NameCloseListener(this));

            TextView save = new TextView(ctx);
            save.setText("保存");
            save.setTextSize(14);
            save.setTypeface(Typeface.DEFAULT_BOLD);
            save.setTextColor(0xFFFFFFFF);
            save.setGravity(Gravity.CENTER);
            GradientDrawable sbg = new GradientDrawable();
            sbg.setColor(C_ACCENT);
            sbg.setCornerRadius(12 * d);
            save.setBackgroundDrawable(sbg);
            save.setPadding(0, dp(d, 11), 0, dp(d, 11));
            LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            svlp.leftMargin = dp(d, 6);
            btns.addView(save, svlp);
            save.setOnClickListener(new NameSaveListener(this));
            box.addView(btns);

            dlg = new AlertDialog.Builder(ctx).setView(box).create();
            refreshList();
        }

        void show() {
            dlg.show();
            try {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                dlg.getWindow().setLayout((int) (dm.widthPixels * 0.86f),
                        (int) (dm.heightPixels * 0.55f));
                GradientDrawable wbg = new GradientDrawable();
                wbg.setColor(0xFFFFFFFF);
                wbg.setCornerRadius(dp(d, 20));
                dlg.getWindow().setBackgroundDrawable(wbg);
            } catch (Throwable ignored) {
            }
        }

        void dismiss() {
            try {
                dlg.dismiss();
            } catch (Throwable ignored) {
            }
        }

        void addFromInput() {
            try {
                String t = input.getText() == null ? "" : input.getText().toString().trim();
                if (t.isEmpty()) {
                    Toast.makeText(ctx, "请先输入名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (t.length() > 24) {
                    Toast.makeText(ctx, "名称太长", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (names.contains(t)) {
                    Toast.makeText(ctx, "已在名单中", Toast.LENGTH_SHORT).show();
                    return;
                }
                names.add(t);
                input.setText("");
                refreshList();
            } catch (Throwable ignored) {
            }
        }

        void removeName(String name) {
            try {
                names.remove(name);
                refreshList();
            } catch (Throwable ignored) {
            }
        }

        void save() {
            try {
                if (kind == 1) {
                    MainHook.setSidebarHideRightNames(names);
                } else if (kind == 2) {
                    MainHook.setProfileEntryNames(names);
                } else {
                    MainHook.setSidebarHideNames(names);
                }
                if (summary != null) summary.setText(namesSummary(names));
                Toast.makeText(ctx, "已保存 " + names.size() + " 个名称，重启抖音后生效",
                        Toast.LENGTH_SHORT).show();
                dismiss();
            } catch (Throwable ignored) {
            }
        }

        void refreshList() {
            try {
                listBox.removeAllViews();
                if (names.isEmpty()) {
                    TextView empty = new TextView(ctx);
                    empty.setText("还没有添加任何名称");
                    empty.setTextSize(12);
                    empty.setTextColor(0xFFB49CAC);
                    empty.setPadding(dp(d, 4), dp(d, 10), 0, dp(d, 4));
                    listBox.addView(empty);
                    return;
                }
                for (final String nm : new java.util.ArrayList<>(names)) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    GradientDrawable rbg = new GradientDrawable();
                    rbg.setColor(0xFFF8EFF4);
                    rbg.setCornerRadius(10 * d);
                    row.setBackgroundDrawable(rbg);
                    row.setPadding(dp(d, 14), dp(d, 9), dp(d, 10), dp(d, 9));

                    TextView tv = new TextView(ctx);
                    tv.setText(nm);
                    tv.setTextSize(13);
                    tv.setTextColor(C_TITLE);
                    row.addView(tv, new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    TextView del = new TextView(ctx);
                    del.setText("删除");
                    del.setTextSize(12);
                    del.setTextColor(C_ACCENT);
                    del.setPadding(dp(d, 10), dp(d, 4), dp(d, 4), dp(d, 4));
                    row.addView(del, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    del.setOnClickListener(new NameDelListener(this, nm));

                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = dp(d, 7);
                    listBox.addView(row, rlp);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 「添加」按钮 */
    private static class NameAddListener implements View.OnClickListener {
        private final NameEditor ed;

        NameAddListener(NameEditor ed) {
            this.ed = ed;
        }

        @Override
        public void onClick(View v) {
            ed.addFromInput();
        }
    }

    /** 条目右侧「删除」 */
    private static class NameDelListener implements View.OnClickListener {
        private final NameEditor ed;
        private final String name;

        NameDelListener(NameEditor ed, String name) {
            this.ed = ed;
            this.name = name;
        }

        @Override
        public void onClick(View v) {
            ed.removeName(name);
        }
    }

    /** 「保存」按钮 */
    private static class NameSaveListener implements View.OnClickListener {
        private final NameEditor ed;

        NameSaveListener(NameEditor ed) {
            this.ed = ed;
        }

        @Override
        public void onClick(View v) {
            ed.save();
        }
    }

    /** 「取消」按钮 */
    private static class NameCloseListener implements View.OnClickListener {
        private final NameEditor ed;

        NameCloseListener(NameEditor ed) {
            this.ed = ed;
        }

        @Override
        public void onClick(View v) {
            ed.dismiss();
        }
    }

    /** 名单摘要文案 */
    private static String sidebarNamesSummary() {
        try {
            java.util.List<String> list = MainHook.getSidebarHideNameList();
            if (list.isEmpty()) return "当前名单：空";
            StringBuilder sb = new StringBuilder("当前名单：");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(list.get(i));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "当前名单：空";
        }
    }

    /** 读取隐藏面某一项的当前开关状态 */
    private static boolean itemOn(String key) {
        if ("topbar".equals(key)) return MainHook.isTopbarHidden();
        if ("visitor".equals(key)) return MainHook.isVisitorHidden();
        if ("interact".equals(key)) return MainHook.isInteractHidden();
        if ("blockEnter".equals(key)) return MainHook.isBlockEnterHidden();
        if ("cleanStorage".equals(key)) return MainHook.isCleanStorageHidden();
        if ("profileEntry".equals(key)) return MainHook.isProfileEntryEnabled();
        if ("profileEntry".equals(key)) return MainHook.isProfileEntryEnabled();
        return MainHook.isFeatureOn(key);
    }

    /** 固定 Switch 颜色：自绘轨道（开=玫粉、关=浅灰胶囊），拇指白色，任何页面完全一致 */
    private static void tintSwitch(Switch sw) {
        try {
            android.graphics.drawable.StateListDrawable track =
                    new android.graphics.drawable.StateListDrawable();
            track.addState(new int[]{android.R.attr.state_checked},
                    trackShape(C_ACCENT));
            track.addState(new int[]{}, trackShape(0xFFE4DAE8));
            sw.setTrackDrawable(track);
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        } catch (Throwable ignored) {
        }
    }

    /** 胶囊形轨道条 */
    private static GradientDrawable trackShape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(999); // 超过半径自动收敛为胶囊形
        return g;
    }

    /** 单个功能项：第一行（名称 + Switch 右侧），第二行小字介绍与名称左对齐 */
    private static View faceItem(Context ctx, float d, String[] item,
                                 Map<String, Switch> boxes, Switch master) {
        LinearLayout unit = new LinearLayout(ctx);
        unit.setOrientation(LinearLayout.VERTICAL);
        unit.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText(item[1]);
        label.setTextSize(14);
        label.setTextColor(C_TITLE);
        label.setPadding(0, 0, dp(d, 8), 0);
        top.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(itemOn(item[0]));
        tintSwitch(sw);
        if ("topbar".equals(item[0])) {
            sw.setOnCheckedChangeListener(new RestartTipListener());
        }
        if ("profileEntry".equals(item[0])) {
            // 拨动即提交「全部勾选状态」，否则只写本项会把未提交的 features 冲掉
            sw.setOnCheckedChangeListener(new SyncAllListener(ctx, master, boxes));
        }
        top.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boxes.put(item[0], sw);

        unit.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(ctx);
        desc.setText(item[2]);
        desc.setTextSize(11);
        desc.setTextColor(C_SUB);
        desc.setPadding(0, dp(d, 2), 0, 0);
        unit.addView(desc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return unit;
    }

    /** 加载模块自身的应用图标（只取资源不加载代码；独立于宿主抖音） */
    private static android.graphics.drawable.Drawable loadModuleIcon(Context ctx) {
        try {
            Context mctx = ctx.createPackageContext(
                    "com.secret.dyhide", Context.CONTEXT_IGNORE_SECURITY);
            int id = mctx.getResources().getIdentifier(
                    "ic_launcher", "mipmap", "com.secret.dyhide");
            if (id != 0) return mctx.getResources().getDrawable(id);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 把一行控件包进白色圆角卡片 */
    private static LinearLayout cardOf(Context ctx, float d, View content) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(d, 16));
        card.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(d, 4);
        card.setLayoutParams(clp);
        card.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 分组标签 */
    /** 「恢复」组：临时显示总开关 + 长按判定与提示词 */
    private static View restoreBlock(final Context ctx, float d,
                                     final Map<String, Switch> boxes, final Switch master,
                                     final TextView countView) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        Switch sw = new Switch(ctx);
        sw.setText("临时显示（长按消息页标题「消息」）");
        sw.setTextSize(13);
        sw.setTextColor(C_TITLE);
        sw.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 10));
        sw.setChecked(MainHook.isTempDisplayEnabled());
        sw.setOnCheckedChangeListener(new SyncAllListener(ctx, master, boxes));
        tintSwitch(sw);
        box.addView(sw);
        boxes.put("tempDisplay", sw);

        TextView hint = new TextView(ctx);
        hint.setText("长按后临时恢复：消息列表会话、禁止进入、视频/直播分享面板与头像条；"
                + "其余隐藏继续生效。重启抖音自动失效，不改写配置文件。");
        hint.setTextSize(11);
        hint.setTextColor(C_SUB);
        hint.setPadding(dp(d, 14), 0, dp(d, 14), dp(d, 8));
        box.addView(hint);

        box.addView(divider(ctx, d, dp(d, 14)));
        box.addView(optionRow(ctx, d, "长按判定时长（毫秒）",
                String.valueOf(MainHook.getLongPressDelay()), 0));
        box.addView(divider(ctx, d, dp(d, 14)));
        box.addView(optionRow(ctx, d, "临时恢复提示词",
                preview(MainHook.getTempTipOn(), "默认文案"), 1));
        box.addView(divider(ctx, d, dp(d, 14)));
        box.addView(optionRow(ctx, d, "恢复隐藏提示词",
                preview(MainHook.getTempTipOff(), "默认文案"), 2));

        // ---- v10：隐藏联系人消息提醒（横幅屏蔽 + 标题红点） ----
        box.addView(divider(ctx, d, dp(d, 14)));
        Switch nmSw = new Switch(ctx);
        nmSw.setText("隐藏联系人消息提醒（横幅屏蔽 + 标题红点）");
        nmSw.setTextSize(13);
        nmSw.setTextColor(C_TITLE);
        nmSw.setPadding(dp(d, 14), dp(d, 10), dp(d, 14), dp(d, 4));
        nmSw.setChecked(MainHook.isNotifyMaskEnabled());
        nmSw.setOnCheckedChangeListener(new SyncAllListener(ctx, master, boxes));
        tintSwitch(nmSw);
        box.addView(nmSw);
        boxes.put("notifyMask", nmSw);

        TextView nmHint = new TextView(ctx);
        nmHint.setText("隐藏联系人来私信时：应用内横幅不弹出，消息页标题「消息」旁出现红点提示。\n注：系统通知栏由厂商推送发出，无法改写。");
        nmHint.setTextSize(11);
        nmHint.setTextColor(C_SUB);
        nmHint.setPadding(dp(d, 14), 0, dp(d, 14), dp(d, 8));
        box.addView(nmHint);

        // ---- 选择隐藏联系人（与面板底部的「重新适配检测」互换位置与描述） ----
        box.addView(divider(ctx, d, dp(d, 14)));
        TextView pickHint = new TextView(ctx);
        pickHint.setText("从好友池勾选要隐藏的联系人，保存后立即生效");
        pickHint.setTextSize(11);
        pickHint.setTextColor(C_SUB);
        pickHint.setPadding(dp(d, 14), dp(d, 6), dp(d, 14), 0);
        box.addView(pickHint);

        TextView pick = new TextView(ctx);
        pick.setText("选择隐藏联系人…");
        pick.setTextSize(13);
        pick.setTypeface(Typeface.DEFAULT_BOLD);
        pick.setTextColor(0xFFFFFFFF);
        pick.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_PINK, C_PURPLE});
        pbg.setCornerRadius(dp(d, 12));
        pick.setBackgroundDrawable(pbg);
        pick.setPadding(0, dp(d, 10), 0, dp(d, 10));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(d, 8);
        box.addView(pick, plp);
        pick.setOnClickListener(new PickClickListener(ctx, countView));
        return box;
    }

    /** 点击「重新适配检测」：先弹二次确认（樱粉紫美化版），防误触。 */
    static final class RecheckClick implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            try {
                Context ctx = v.getContext();
                float d = ctx.getResources().getDisplayMetrics().density;

                LinearLayout box = new LinearLayout(ctx);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(d, 22), dp(d, 20), dp(d, 22), dp(d, 18));

                TextView t = new TextView(ctx);
                t.setText("重新适配检测");
                t.setTextSize(19);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(C_TITLE);
                box.addView(t);

                // 渐变下划线
                View line = new View(ctx);
                GradientDrawable lg = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[]{C_PINK, C_PURPLE});
                lg.setCornerRadius(dp(d, 2));
                line.setBackgroundDrawable(lg);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(d, 46), dp(d, 3));
                llp.topMargin = dp(d, 8);
                line.setLayoutParams(llp);
                box.addView(line);

                TextView msg = new TextView(ctx);
                msg.setText("将重新扫描抖音 dex 并更新适配映射，耗时约 1 分钟。\n\n"
                        + "检测完成后会提示重启抖音，动态类名才能生效。\n\n确定继续吗？");
                msg.setTextSize(13);
                msg.setTextColor(C_SUB);
                msg.setLineSpacing(0, 1.25f);
                msg.setPadding(0, dp(d, 16), 0, dp(d, 6));
                box.addView(msg);

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(d, 10), 0, 0);

                TextView cancel = new TextView(ctx);
                cancel.setText("取消");
                cancel.setTextSize(14);
                cancel.setTextColor(C_SUB);
                cancel.setGravity(Gravity.CENTER);
                GradientDrawable cbg = new GradientDrawable();
                cbg.setColor(0xFFF1E7ED);
                cbg.setCornerRadius(dp(d, 11));
                cancel.setBackgroundDrawable(cbg);
                cancel.setPadding(0, dp(d, 11), 0, dp(d, 11));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.rightMargin = dp(d, 6);
                cancel.setLayoutParams(clp);
                row.addView(cancel);

                TextView ok = new TextView(ctx);
                ok.setText("确定开始");
                ok.setTextSize(14);
                ok.setTypeface(Typeface.DEFAULT_BOLD);
                ok.setTextColor(0xFFFFFFFF);
                ok.setGravity(Gravity.CENTER);
                GradientDrawable obg = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[]{C_PINK, C_PURPLE});
                obg.setCornerRadius(dp(d, 11));
                ok.setBackgroundDrawable(obg);
                ok.setPadding(0, dp(d, 11), 0, dp(d, 11));
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                olp.leftMargin = dp(d, 6);
                ok.setLayoutParams(olp);
                row.addView(ok);
                box.addView(row);

                final AlertDialog dialog = new AlertDialog.Builder(ctx)
                        .setView(box).create();
                cancel.setOnClickListener(new RecheckCancelView(dialog));
                ok.setOnClickListener(new RecheckConfirmView(ctx, dialog));
                dialog.show();
                try {
                    if (dialog.getWindow() != null) {
                        GradientDrawable wbg = new GradientDrawable();
                        wbg.setColor(0xFFFAF5F8);
                        wbg.setCornerRadius(dp(d, 20));
                        dialog.getWindow().setBackgroundDrawable(wbg);
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 美化确认框：确定（View 版） */
    static final class RecheckConfirmView implements View.OnClickListener {
        private final Context ctx;
        private final AlertDialog dialog;
        RecheckConfirmView(Context ctx, AlertDialog dialog) {
            this.ctx = ctx; this.dialog = dialog;
        }
        @Override public void onClick(View v) {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
            try {
                if (ctx != null) Toast.makeText(ctx, "开始重新适配检测…", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
            try {
                AlertDialog p = PANEL_DLG.get();
                if (p != null && p.isShowing()) p.dismiss();
            } catch (Throwable ignored) {}
            MainHook.requestRecheck(ctx);
        }
    }

    /** 美化确认框：取消（View 版） */
    static final class RecheckCancelView implements View.OnClickListener {
        private final AlertDialog dialog;
        RecheckCancelView(AlertDialog dialog) { this.dialog = dialog; }
        @Override public void onClick(View v) {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
        }
    }

    /** 确认：关闭设置面板并重新触发适配检测。 */
    static final class RecheckConfirm implements android.content.DialogInterface.OnClickListener {
        private final Context ctx;
        RecheckConfirm(Context ctx) { this.ctx = ctx; }
        @Override
        public void onClick(android.content.DialogInterface d, int which) {
            try {
                if (ctx != null) Toast.makeText(ctx, "开始重新适配检测…", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            try {
                AlertDialog p = PANEL_DLG.get();
                if (p != null && p.isShowing()) p.dismiss();
            } catch (Throwable ignored) {
            }
            // v17：面板可能在子进程，发广播让主进程执行 rerun（主进程才有 MainActivity）
            MainHook.requestRecheck(ctx);
        }
    }

    /** 取消：什么也不做。 */
    static final class RecheckCancel implements android.content.DialogInterface.OnClickListener {
        @Override
        public void onClick(android.content.DialogInterface d, int which) {
            try { d.dismiss(); } catch (Throwable ignored) {}
        }
    }

    private static String preview(String v, String def) {
        if (v == null || v.trim().isEmpty()) return def;
        String t = v.trim();
        return t.length() > 10 ? t.substring(0, 10) + "…" : t;
    }

    private static View optionRow(final Context ctx, float d, final String title,
                                  String value, final int type) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(d, 14), dp(d, 7), dp(d, 14), dp(d, 7));
        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(12);
        label.setTextColor(C_TITLE);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);
        final Button btn = new Button(ctx);
        btn.setText(value);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showOptionDialog(ctx, type, btn); }
        });
        stylePill(btn, false);
        row.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void showOptionDialog(final Context ctx, final int type, final Button btn) {
        float d = ctx.getResources().getDisplayMetrics().density;
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(d, 20), dp(d, 18), dp(d, 20), dp(d, 16));

        // 标题：左侧玫粉竖条 + 文案（与面板其它弹窗一致）
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(ctx);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(C_ACCENT);
        barBg.setCornerRadius(dp(d, 2));
        bar.setBackgroundDrawable(barBg);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(d, 4), dp(d, 17));
        barLp.rightMargin = dp(d, 9);
        head.addView(bar, barLp);
        TextView title = new TextView(ctx);
        title.setText(type == 0 ? "长按判定时长（毫秒）"
                : (type == 1 ? "临时恢复后的提示词"
                : "恢复隐藏后的提示词"));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TITLE);
        head.addView(title);
        box.addView(head);

        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(C_TITLE);
        input.setHintTextColor(0xFFB49CAC);
        input.setText(type == 0 ? String.valueOf(MainHook.getLongPressDelay())
                : (type == 1 ? MainHook.getTempTipOn()
                : MainHook.getTempTipOff()));
        if (type == 0) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(type == 0 ? "100-5000" : "留空使用默认文案");
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(0xFFF8EFF4);
        ibg.setCornerRadius(dp(d, 10));
        ibg.setStroke(dp(d, 1), 0xFFE6BCD0);
        input.setBackgroundDrawable(ibg);
        input.setPadding(dp(d, 13), dp(d, 10), dp(d, 13), dp(d, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(d, 14);
        box.addView(input, ilp);

        TextView tip = new TextView(ctx);
        tip.setText(type == 0 ? "可设 100-5000 毫秒，留空或非法值按 500 处理"
                : (type == 1 ? "长按临时恢复后弹出的提示，留空使用默认文案"
                : "再次长按恢复隐藏后弹出的提示，留空使用默认文案"));
        tip.setTextSize(11);
        tip.setTextColor(C_SUB);
        tip.setPadding(0, dp(d, 7), 0, 0);
        box.addView(tip);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(d, 16), 0, 0);

        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(C_SUB);
        cancel.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFFF1E7ED);
        cbg.setCornerRadius(dp(d, 11));
        cancel.setBackgroundDrawable(cbg);
        cancel.setPadding(0, dp(d, 11), 0, dp(d, 11));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(d, 6);
        btns.addView(cancel, clp);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { holder[0].dismiss(); }
        });

        TextView save = new TextView(ctx);
        save.setText("保存");
        save.setTextSize(14);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(0xFFFFFFFF);
        save.setGravity(Gravity.CENTER);
        GradientDrawable sbg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{C_PINK, C_PURPLE});
        sbg.setCornerRadius(dp(d, 11));
        save.setBackgroundDrawable(sbg);
        save.setPadding(0, dp(d, 11), 0, dp(d, 11));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(d, 6);
        btns.addView(save, slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = input.getText().toString().trim();
                if (type == 0) {
                    int ms;
                    try { ms = Integer.parseInt(t); }
                    catch (Throwable e) { ms = 500; }
                    MainHook.setLongPressOptions(ms,
                            MainHook.getTempTipOn(), MainHook.getTempTipOff());
                    btn.setText(String.valueOf(MainHook.getLongPressDelay()));
                } else if (type == 1) {
                    MainHook.setLongPressOptions(MainHook.getLongPressDelay(),
                            t, MainHook.getTempTipOff());
                    btn.setText(preview(MainHook.getTempTipOn(), "默认文案"));
                } else if (type == 2) {
                    MainHook.setLongPressOptions(MainHook.getLongPressDelay(),
                            MainHook.getTempTipOn(), t);
                    btn.setText(preview(MainHook.getTempTipOff(), "默认文案"));
                }
                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show();
                holder[0].dismiss();
            }
        });
        box.addView(btns);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(box).create();
        holder[0] = dlg;
        dlg.show();
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            dlg.getWindow().setLayout((int) (dm.widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            GradientDrawable wbg = new GradientDrawable();
            wbg.setColor(0xFFFFFFFF);
            wbg.setCornerRadius(dp(d, 20));
            dlg.getWindow().setBackgroundDrawable(wbg);
        } catch (Throwable ignored) {
        }
    }

    /** 分组标题点击展开/收起 */
    private static void attachCollapse(final Context ctx, float d, final TextView head,
                                       final LinearLayout card, final String name) {
        final boolean[] collapsed = { COLLAPSED_GROUPS.contains(name) };
        head.setText((collapsed[0] ? "▸  " : "▾  ") + name);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsed[0] = !collapsed[0];
                if (collapsed[0]) COLLAPSED_GROUPS.add(name);
                else COLLAPSED_GROUPS.remove(name);
                card.setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
                head.setText((collapsed[0] ? "▸  " : "▾  ") + name);
            }
        });
        card.setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
    }

    /** 圆角胶囊按钮：已设置有渐变底色，未设置为浅色描边 */
    private static void stylePill(Button btn, boolean active) {
        float d = btn.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(d, 9));
        if (active) {
            gd.setColors(new int[]{C_PINK, C_PURPLE});
            btn.setTextColor(0xFFFFFFFF);
        } else {
            gd.setColor(0xFFFFF0F5);
            gd.setStroke(dp(d, 1), 0xFFE6BCD0);
            btn.setTextColor(C_ACCENT);
        }
        btn.setBackgroundDrawable(gd);
        btn.setTextSize(11);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(dp(d, 12), dp(d, 3), dp(d, 12), dp(d, 3));
    }

    private static TextView groupLabel(Context ctx, float d, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(C_SUB);
        tv.setPadding(dp(d, 6), dp(d, 14), 0, dp(d, 6));
        return tv;
    }

    /** 细分隔线 */
    private static View divider(Context ctx, float d, int leftMargin) {
        View v = new View(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF3E6EE);
        bg.setSize(1, dp(d, 1));
        v.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(d, 1));
        lp.leftMargin = leftMargin;
        v.setLayoutParams(lp);
        return v;
    }

    private static int dp(float d, int v) {
        return (int) (v * d);
    }

    // ================= 事件 =================

    private static class PickClickListener implements View.OnClickListener {
        private final Context ctx;
        private final TextView countView;

        PickClickListener(Context ctx, TextView countView) {
            this.ctx = ctx;
            this.countView = countView;
        }

        @Override
        public void onClick(View v) {
            FriendPicker.show(ctx, countView);
        }
    }

    private static class DialogShowListener implements DialogInterface.OnShowListener {
        private final AlertDialog dlg;
        private final Switch master;
        private final Map<String, Switch> boxes;

        DialogShowListener(AlertDialog dlg, Switch master, Map<String, Switch> boxes) {
            this.dlg = dlg;
            this.master = master;
            this.boxes = boxes;
        }

        @Override
        public void onShow(DialogInterface d) {
            // "保存"按钮默认会关闭对话框，这里改成保存后关闭
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    new SaveClickListener(dlg, master, boxes));
        }
    }

    /** 提交面板里所有勾选状态：直接用界面值写盘，返回是否真正落盘 */
    private static boolean commitAll(Context ctx, Switch master, Map<String, Switch> boxes) {
        List<String> on = new ArrayList<>();
        // 先挂起写盘：下面每个 setter 都会 persistCurrent，会拿"旧 features"反复写盘，
        // 中间只要有一次落在最后一次之后，文件就被旧值覆盖。统一到最后写一次。
        MainHook.beginBatch();
        try {
            for (int i = 0; i < FACE_ITEMS.length; i++) {
                String key = FACE_ITEMS[i][0];
                Switch sw = boxes.get(key);
                if (sw == null) continue;
                boolean checked = sw.isChecked();
                if ("topbar".equals(key)) MainHook.setTopbarHidden(checked);
                else if ("visitor".equals(key)) MainHook.setVisitorHidden(checked);
                else if ("interact".equals(key)) MainHook.setInteractHidden(checked);
                else if ("blockEnter".equals(key)) MainHook.setBlockEnterHidden(checked);
                else if ("cleanStorage".equals(key)) MainHook.setCleanStorageHidden(checked);
                else if ("profileEntry".equals(key)) MainHook.setProfileEntryEnabled(checked);
                else if ("profileTab".equals(key)) MainHook.setProfileTabEnabled(checked);
                else if (checked) on.add(key);
            }
            Switch tempSw = boxes.get("tempDisplay");
            if (tempSw != null) MainHook.setTempDisplayEnabled(tempSw.isChecked());
            Switch nmSw = boxes.get("notifyMask");
            if (nmSw != null) MainHook.setNotifyMaskEnabled(nmSw.isChecked());
        } finally {
            MainHook.endBatch();
        }
        // 关键：写盘参数全部来自界面上的 Switch，而不是"内存里上一次读到的配置"。
        boolean ok = MainHook.saveAll(ctx, master.isChecked(), on,
                checked(boxes, "topbar"), checked(boxes, "visitor"),
                checked(boxes, "interact"), checked(boxes, "blockEnter"),
                checked(boxes, "cleanStorage"), checked(boxes, "profileEntry"));
        android.util.Log.i("XProHideContacts", "commitAll on=" + on
                + " topbar=" + checked(boxes, "topbar")
                + " interact=" + checked(boxes, "interact")
                + " profileEntry=" + checked(boxes, "profileEntry")
                + " saved=" + ok);
        return ok;
    }

    private static boolean checked(Map<String, Switch> boxes, String key) {
        Switch sw = boxes.get(key);
        return sw != null && sw.isChecked();
    }

    private static class SaveClickListener implements View.OnClickListener {
        private final AlertDialog dlg;
        private final Switch master;
        private final Map<String, Switch> boxes;

        SaveClickListener(AlertDialog dlg, Switch master, Map<String, Switch> boxes) {
            this.dlg = dlg;
            this.master = master;
            this.boxes = boxes;
        }

        @Override
        public void onClick(View v) {
            boolean ok = commitAll(v.getContext(), master, boxes);
            Toast.makeText(v.getContext(), ok ? "已保存" : "保存失败：配置文件不可写",
                    Toast.LENGTH_LONG).show();
            if (ok) dlg.dismiss();
        }
    }

    /** 即时开关：拨动即提交全部勾选状态 */
    private static class SyncAllListener
            implements android.widget.CompoundButton.OnCheckedChangeListener {
        private final Context ctx;
        private final Switch master;
        private final Map<String, Switch> boxes;

        SyncAllListener(Context ctx, Switch master, Map<String, Switch> boxes) {
            this.ctx = ctx;
            this.master = master;
            this.boxes = boxes;
        }

        @Override
        public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
            try {
                boolean ok = commitAll(ctx, master, boxes);
                Toast.makeText(ctx, ok ? "已保存" : "保存失败：配置文件不可写",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                android.util.Log.e("XProHideContacts", "sync save failed: "
                        + android.util.Log.getStackTraceString(t));
            }
        }
    }
}
