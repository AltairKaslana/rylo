package com.example.ytmplayer;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("ytm", Context.MODE_PRIVATE);
    }

    public static String server(Context c) { return sp(c).getString("server", ""); }

    public static String token(Context c) { return sp(c).getString("token", ""); }

    public static boolean ready(Context c) { return !server(c).isEmpty() && !token(c).isEmpty(); }

    public static void save(Context c, String server, String token) {
        String s = server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.isEmpty() && !s.startsWith("http")) s = "http://" + s;
        sp(c).edit().putString("server", s).putString("token", token.trim()).apply();
    }
}
