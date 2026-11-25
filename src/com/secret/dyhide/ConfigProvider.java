package com.secret.dyhide;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** 供抖音进程（hook 侧）查询配置：content://com.secret.dyhide.config/ */
public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        Context c = getContext();
        SharedPreferences sp = c == null ? null
                : c.getSharedPreferences("hid_config", Context.MODE_PRIVATE);
        boolean en = sp == null || sp.getBoolean("enabled", true);
        MatrixCursor cur = new MatrixCursor(new String[]{"entries", "enabled"});
        // Boolean 直接放进 MatrixCursor 时 getInt 会异常/返回 0，改存字符串
        cur.addRow(new Object[]{
                sp == null ? "" : sp.getString("entries", ""),
                en ? "1" : "0"});
        return cur;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
