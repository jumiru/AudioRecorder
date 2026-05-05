package com.jrgames.audiorecorder.webdav;

import android.content.Context;
import android.content.SharedPreferences;

public class WebDavConfig {
    private static final String PREFS_NAME = "webdav_prefs";
    private static final String KEY_SERVER  = "server";
    private static final String KEY_USER    = "username";
    private static final String KEY_PASS    = "password";
    private static final String KEY_DIR     = "directory";

    public String server;
    public String username;
    public String password;
    public String directory;

    public static WebDavConfig load(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        WebDavConfig cfg = new WebDavConfig();
        cfg.server    = prefs.getString(KEY_SERVER, "");
        cfg.username  = prefs.getString(KEY_USER,   "");
        cfg.password  = prefs.getString(KEY_PASS,   "");
        cfg.directory = prefs.getString(KEY_DIR,    "/");
        return cfg;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER, server)
                .putString(KEY_USER,   username)
                .putString(KEY_PASS,   password)
                .putString(KEY_DIR,    directory)
                .apply();
    }
}

