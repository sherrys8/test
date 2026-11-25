package com.secret.dyhide;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 联系人多选对话框：搜索 + A-Z（拼音）排序 + 已选置顶。
 */
class FriendPicker {

    static void show(Context ctx, TextView countView) {
        List<String[]> friends = MainHook.getFriends();
        if (friends.isEmpty()) {
            Toast.makeText(ctx,
                    "还没有好友数据。请先打开抖音「我 → 互关」列表再试",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new FriendPicker(ctx, friends, countView).showDialog();
    }

    private final Context ctx;
    private final TextView countView;
    private final List<String[]> all;
    private final boolean[] checked;
    /** 当前展示顺序：all 的下标 */
    private final List<Integer> order = new ArrayList<>();
    private String query = "";
    private AlertDialog dlg;
    private FriendAdapter adapter;

    private static final Collator NAME_COLLATOR =
            Collator.getInstance(Locale.SIMPLIFIED_CHINESE);

    private FriendPicker(Context ctx, List<String[]> friends, TextView countView) {
        this.ctx = ctx;
        this.countView = countView;
        this.all = friends;
        this.checked = new boolean[friends.size()];
        for (int i = 0; i < friends.size(); i++) {
            checked[i] = MainHook.isUidHidden(friends.get(i)[0]);
        }
    }

    private void showDialog() {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * d);
        root.setPadding(pad, pad / 2, pad, 0);
        root.setBackgroundColor(0xFFFAF5F8);

        final EditText search = new EditText(ctx);
        search.setHint("搜索昵称、UID");
        search.setMaxLines(1);
        search.setTextSize(14);
        search.setTextColor(0xFF3A2A35);
        search.setHintTextColor(0xFFB49CAC);
        // 浅粉圆角底
        android.graphics.drawable.GradientDrawable sg =
                new android.graphics.drawable.GradientDrawable();
        sg.setColor(0xFFF6E9F0);
        sg.setCornerRadius((int) (12 * d));
        search.setBackgroundDrawable(sg);
        search.setPadding((int) (12 * d), (int) (9 * d), (int) (12 * d), (int) (9 * d));
        root.addView(search);

        // 提醒（标题下方）
        TextView tip = new TextView(ctx);
        tip.setText("如果联系人人数不正常，请回到个人主页-互关中，从上往下拉一遍");
        tip.setTextSize(11);
        tip.setTextColor(0xFFD96C9F);
        tip.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), (int) (2 * d));
        root.addView(tip);

        adapter = new FriendAdapter(ctx, all, checked, order);
        ListView lv = new ListView(ctx);
        lv.setAdapter(adapter);
        lv.setDivider(null);
        lv.setVerticalScrollBarEnabled(false);
        lv.setOnItemClickListener(new RowClickListener(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(lv, lp);

        rebuild();

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(title());
        b.setView(root);
        b.setPositiveButton("确定", new PickerOkListener(this));
        b.setNegativeButton("取消", null);
        dlg = b.show();

        // 圆角窗口 + 底色
        try {
            if (dlg.getWindow() != null) {
                android.graphics.drawable.GradientDrawable winBg =
                        new android.graphics.drawable.GradientDrawable();
                winBg.setColor(0xFFFAF5F8);
                winBg.setCornerRadius((int) (18 * d));
                dlg.getWindow().setBackgroundDrawable(winBg);
            }
        } catch (Throwable ignored) {
        }
        // 按钮染色（show 之后）
        try {
            android.widget.Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            android.widget.Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (pos != null) pos.setTextColor(0xFFD96C9F);
            if (neg != null) neg.setTextColor(0xFF967E8E);
        } catch (Throwable ignored) {
        }

        search.addTextChangedListener(new SearchWatcher(this));

        // 动态适配屏幕：高 60%、宽 88%（左右留间隔），任意手机一致
        if (dlg.getWindow() != null) {
            try {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                int winW = (int) (dm.widthPixels * 0.88f);
                int winH = (int) (dm.heightPixels * 0.60f);
                dlg.getWindow().setLayout(winW, winH);
            } catch (Throwable ignored) {
                dlg.getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (560 * d));
            }
        }
    }

    private String title() {
        return "选择要隐藏的联系人（" + countChecked() + "/" + all.size() + "）";
    }

    private int countChecked() {
        int n = 0;
        for (boolean c : checked) if (c) n++;
        return n;
    }

    /** 重排：已选置顶 → A-Z（中文拼音序）→ 按搜索词过滤。手写插入排序（绕开 d8 泛型 bug）。 */
    private void rebuild() {
        int n = all.size();
        int[] ord = new int[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        for (int i = 1; i < n; i++) {
            int cur = ord[i];
            int j = i - 1;
            while (j >= 0 && lessThan(cur, ord[j])) {
                ord[j + 1] = ord[j];
                j--;
            }
            ord[j + 1] = cur;
        }
        order.clear();
        String q = query.toLowerCase(Locale.ROOT);
        for (int idx : ord) {
            if (q.isEmpty()
                    || all.get(idx)[1].toLowerCase(Locale.ROOT).contains(q)
                    || all.get(idx)[0].contains(query)) {
                order.add(idx);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (dlg != null) dlg.setTitle(title());
    }

    /** 排序规则：已选在前，其余按昵称 A-Z（拼音），同名单按 uid。 */
    private boolean lessThan(int a, int b) {
        if (checked[a] != checked[b]) return checked[a];
        int r = NAME_COLLATOR.compare(all.get(a)[1], all.get(b)[1]);
        if (r != 0) return r < 0;
        return all.get(a)[0].compareTo(all.get(b)[0]) < 0;
    }

    private static class RowClickListener implements AdapterView.OnItemClickListener {
        private final FriendPicker host;

        RowClickListener(FriendPicker host) {
            this.host = host;
        }
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (position < 0 || position >= host.order.size()) return;
            int idx = host.order.get(position);
            host.checked[idx] = !host.checked[idx];
            host.rebuild();
        }
    }

    private static class SearchWatcher implements TextWatcher {
        private final FriendPicker host;

        SearchWatcher(FriendPicker host) {
            this.host = host;
        }
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b2, int c) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            host.query = s == null ? "" : s.toString().trim();
            host.rebuild();
        }
    }

    private static class PickerOkListener implements DialogInterface.OnClickListener {
        private final FriendPicker host;

        PickerOkListener(FriendPicker host) {
            this.host = host;
        }
        @Override
        public void onClick(DialogInterface d, int which) {
            List<String[]> chosen = new ArrayList<>();
            for (int i = 0; i < host.all.size(); i++) {
                if (host.checked[i]) chosen.add(host.all.get(i));
            }
            MainHook.setHiddenContacts(chosen);
            if (host.countView != null) {
                // 与面板初始格式一致，保持一行
                host.countView.setText("已隐藏 " + chosen.size() + " 人 · 好友池 "
                        + host.all.size() + " 人");
            }
            Toast.makeText(host.ctx, "已隐藏 " + chosen.size() + " 个联系人", Toast.LENGTH_SHORT).show();
        }
    }

    private static class FriendAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<String[]> all;
        private final boolean[] checked;
        private final List<Integer> order;

        FriendAdapter(Context ctx, List<String[]> all, boolean[] checked, List<Integer> order) {
            this.ctx = ctx;
            this.all = all;
            this.checked = checked;
            this.order = order;
        }

        @Override
        public int getCount() {
            return order.size();
        }

        @Override
        public Object getItem(int position) {
            return all.get(order.get(position));
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout row;
            CheckBox cb;
            TextView name;
            TextView uid;
            if (convertView instanceof LinearLayout
                    && ((LinearLayout) convertView).getChildCount() == 2) {
                row = (LinearLayout) convertView;
                cb = (CheckBox) row.getChildAt(0);
                LinearLayout txt = (LinearLayout) row.getChildAt(1);
                name = (TextView) txt.getChildAt(0);
                uid = (TextView) txt.getChildAt(1);
            } else {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = (int) (12 * d);
                row.setPadding(pad, (int) (8 * d), pad, (int) (8 * d));
                LinearLayout txt = new LinearLayout(ctx);
                txt.setOrientation(LinearLayout.VERTICAL);
                name = new TextView(ctx);
                name.setTextSize(15);
                name.setTextColor(0xFF3A2A35);
                name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                txt.addView(name);
                uid = new TextView(ctx);
                uid.setTextSize(11);
                uid.setTextColor(0xFF967E8E);
                uid.setPadding(0, (int) (2 * d), 0, 0);
                txt.addView(uid);
                cb = new CheckBox(ctx);
                cb.setFocusable(false);
                cb.setClickable(false);
                // 固定勾选颜色：选中=玫粉、未选中=浅灰，不受抖音页面主题影响
                try {
                    int[][] states = new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    };
                    cb.setButtonTintList(new android.content.res.ColorStateList(
                            states, new int[]{0xFFD96C9F, 0xFFB49CAC}));
                } catch (Throwable ignored) {
                }
                LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mid.rightMargin = (int) (8 * d);
                row.addView(cb, mid);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(txt, tlp);
            }
            int idx = order.get(position);
            String[] fr = all.get(idx);
            cb.setChecked(checked[idx]);
            name.setText(fr[1]);
            uid.setText("UID：" + fr[0]);
            // 已选行浅粉高亮
            if (checked[idx]) {
                row.setBackgroundColor(0x14D96C9F);
            } else {
                row.setBackgroundColor(0x00000000);
            }
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }
    }
}
