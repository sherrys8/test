package com.secret.dyhide;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 独立 App 主页（v11.0，仿 xfhide 风格）：
 * 全屏背景图打底 + 半透明圆角卡片，hero 卡片在滚动区内（不固定顶部）。
 * 配置统一在抖音内任意输入框输入 #dy 打开的面板中完成。
 */
public class SettingsActivity extends Activity {

    /** 功能分组名（与 #dy 面板一致） */
    private static final String[] FEATURE_GROUPS = {"恢复", "消息列表", "视频", "主页", "侧边栏"};

    /** 各组的功能项：显示名 → 说明（与 #dy 面板逐条对齐，v11.0 全量更新） */
    private static final String[][][] FEATURE_ITEMS = {
            {
                    {"临时显示（长按消息页标题「消息」）", "长按消息页顶部「消息」临时恢复会话列表、进入拦截与分享面板；时长和提示词在 #dy 面板设置"},
                    {"隐藏联系人消息提醒（横幅屏蔽 + 标题红点）", "隐藏联系人来私信时：应用内横幅不弹出，消息页标题「消息」旁出现红点提示；系统通知栏由厂商推送发出，无法改写"},
            },
            {
                    {"隐藏消息列表会话", "隐藏消息页会话列表中的指定联系人"},
                    {"隐藏消息页顶部头像条", "将消息页顶部「在线好友」头像栏整条隐藏"},
                    {"隐藏互动消息（点赞/来访等）", "隐藏互动消息列表中的指定联系人"},
                    {"隐藏搜索 / 更多聊天", "隐藏 IM 搜索结果与「更多聊天」中的指定联系人"},
                    {"禁止进入聊天页 / 主页", "点击隐藏联系人时拦截其聊天页与个人主页"},
            },
            {
                    {"隐藏视频 / 直播分享面板 / 头像条", "隐藏视频、直播的分享面板与联系人头像条中的指定联系人"},
                    {"隐藏一起看邀请面板", "隐藏「一起看」邀请面板中的指定联系人"},
            },
            {
                    {"隐藏关注 / 粉丝 / 互关 / 朋友列表", "隐藏四个关系列表及粉丝搜索中的指定联系人"},
                    {"隐藏主页访客记录", "隐藏个人主页顶部「访客」入口中的指定联系人"},
                    {"隐藏聊天记录清理页", "从「清理缓存 → 聊天记录」管理页数据中移除指定联系人会话"},
                    {"隐藏个人主页卡片入口", "开启后勾选要隐藏的入口；不勾选不隐藏，全选才隐藏整排"},
                    {"隐藏个人主页标签", "隐藏「我」页标签栏：作品/日常/收藏/喜欢/去过"},
            },
            {
                    {"自定义删除左侧侧滑栏入口", "按名单删除左侧「我」页侧滑栏中的入口项"},
                    {"自定义删除右侧侧滑栏入口", "按名单删除右侧首页侧滑栏中的入口项"},
            },
    };

    private static final String TARGET_PKG = "com.ss.android.ugc.aweme";

    // ---- 樱粉紫配色 ----
    private static final int C_BG       = 0xFFFAF5F8;
    private static final int C_TITLE    = 0xFF3A2A35;
    private static final int C_SUB      = 0xFF8A7080;
    private static final int C_ACCENT   = 0xFFD96C9F;
    private static final int CARD_BG    = 0x99FFFFFF; // 半透明白卡片
    private static final int BTN_BG     = 0xB3FFFFFF; // 半透明白按钮
    private static final int CHIP_ON_BG = 0x1FD96C9F; // chip 激活底
    private static final int DOT_ON     = 0xFF3BB273; // 绿点
    private static final int DOT_OFF    = 0xFFE25454; // 红点
    private static final int DIVIDER    = 0x1F3A2A35; // 半透明分隔线

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float d = getResources().getDisplayMetrics().density;

        // 沉浸式：透明状态栏 + 深色状态栏文字（背景图透出），无任何固定标题区
        try {
            Window w = getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(0x00000000);
            int fl = w.getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            w.getDecorView().setSystemUiVisibility(fl);
            w.setNavigationBarColor(0x00000000);
        } catch (Throwable ignored) {
        }

        // ===== 根布局：背景图 + 滚动内容 =====
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        ImageView bg = new ImageView(this);
        Bitmap bgBmp = loadBitmap("app_bg", "drawable");
        if (bgBmp != null) {
            bg.setImageBitmap(bgBmp);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        bg.setAlpha(0.3f);
        root.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(d, 14), dp(d, 24), dp(d, 14), dp(d, 20));
        sv.addView(body, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ===== hero 卡片（在滚动区内，不固定，紧凑尺寸） =====
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable heroBg = new GradientDrawable();
        heroBg.setColor(CARD_BG);
        heroBg.setCornerRadius(dp(d, 20));
        hero.setBackgroundDrawable(heroBg);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(d, 6);
        hero.setLayoutParams(heroLp);

        LinearLayout heroLeft = new LinearLayout(this);
        heroLeft.setOrientation(LinearLayout.VERTICAL);
        heroLeft.setPadding(dp(d, 16), dp(d, 18), dp(d, 16), dp(d, 18));

        ImageView icon = new ImageView(this);
        Bitmap ic = loadBitmap("ic_launcher", "mipmap");
        if (ic == null) ic = loadBitmap("ic_launcher", "drawable");
        if (ic != null) icon.setImageBitmap(ic);
        heroLeft.addView(icon, new LinearLayout.LayoutParams(dp(d, 44), dp(d, 44)));

        TextView name = new TextView(this);
        name.setText("dyhide");
        name.setTextSize(22);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(C_TITLE);
        name.setPadding(0, dp(d, 10), 0, 0);
        heroLeft.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tagline = new TextView(this);
        tagline.setText("按名单隐藏抖音联系人 · 配置在抖音内 #dy 面板完成");
        tagline.setTextSize(11);
        tagline.setTextColor(C_SUB);
        tagline.setPadding(0, dp(d, 5), 0, 0);
        heroLeft.addView(tagline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hero.addView(heroLeft, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body.addView(hero);

        // ===== 使用方法 =====
        body.addView(sectionHead(d, "使用方法"));
        LinearLayout howCard = card(d, dp(d, 16));
        howCard.addView(infoRow(d, "第一步", "打开抖音，进入任意聊天输入框"));
        howCard.addView(divider(d, dp(d, 16)));
        howCard.addView(infoRow(d, "第二步", "输入 #dy 并发送，弹出设置面板"));
        howCard.addView(divider(d, dp(d, 16)));
        howCard.addView(infoRow(d, "第三步", "开启需要的功能、勾选联系人并保存"));
        body.addView(howCard);

        // ===== 功能一览（按组展示） =====
        for (int g = 0; g < FEATURE_GROUPS.length; g++) {
            body.addView(sectionHead(d, "功能一览 · " + FEATURE_GROUPS[g]));
            LinearLayout featCard = card(d, dp(d, 16));
            String[][] items = FEATURE_ITEMS[g];
            for (int i = 0; i < items.length; i++) {
                featCard.addView(infoRow(d, items[i][0], items[i][1]));
                if (i < items.length - 1) featCard.addView(divider(d, dp(d, 16)));
            }
            body.addView(featCard);
        }

        // ===== 底部版本号 =====
        TextView ver = new TextView(this);
        ver.setText("11.0");
        ver.setTextSize(11);
        ver.setTextColor(C_SUB);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(d, 18), 0, 0);
        body.addView(ver, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    /** 读取打包进 APK 的图片资源 */
    private Bitmap loadBitmap(String name, String type) {
        try {
            int id = getResources().getIdentifier(name, type, getPackageName());
            if (id != 0) return BitmapFactory.decodeResource(getResources(), id);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 半透明白圆角内容卡片（垂直排列） */
    private LinearLayout card(float d, int radius) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(radius);
        card.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(d, 6);
        card.setLayoutParams(lp);
        return card;
    }

    /** 分节标题 */
    private TextView sectionHead(float d, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(C_SUB);
        tv.setPadding(dp(d, 4), dp(d, 18), 0, dp(d, 8));
        return tv;
    }

    /** 标题 + 说明行 */
    private LinearLayout infoRow(float d, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(d, 16), dp(d, 13), dp(d, 16), dp(d, 13));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(C_TITLE);
        row.addView(t);
        TextView s = new TextView(this);
        s.setText(desc);
        s.setTextSize(12);
        s.setTextColor(C_SUB);
        s.setPadding(0, dp(d, 5), 0, 0);
        row.addView(s);
        return row;
    }

    /** 细分隔线 */
    private View divider(float d, int leftMargin) {
        View v = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DIVIDER);
        bg.setSize(1, dp(d, 1));
        v.setBackgroundDrawable(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(d, 1));
        lp.leftMargin = leftMargin;
        lp.rightMargin = leftMargin;
        v.setLayoutParams(lp);
        return v;
    }

    private static int dp(float d, int v) {
        return (int) (v * d);
    }
}
