package com.example.multiusershare;

import android.content.Context;
import android.content.SharedPreferences;

final class ConfigStore {
    private static final String PREFS = "share_config";
    private final Context context;
    private final SharedPreferences prefs;

    ConfigStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String username() { return prefs.getString("username", "share"); }
    String password() { return SecurePrefs.get(context, "password", ""); }
    boolean authEnabled() { return prefs.getBoolean("auth_enabled", true); }
    int port() { return prefs.getInt("port", 8080); }

    void save(String username, String password, boolean authEnabled, int port) {
        prefs.edit().putString("username", username).putBoolean("auth_enabled", authEnabled)
                .putInt("port", port).apply();
        SecurePrefs.put(context, "password", password);
    }
}
