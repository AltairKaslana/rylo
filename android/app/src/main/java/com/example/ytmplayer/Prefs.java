package com.example.ytmplayer;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("ytm", Context.MODE_PRIVATE);
    }

    /** Alamat bawaan supaya tidak perlu mengetik panjang saat pertama pasang. */
    public static final String DEFAULT_SERVER = "http://bore.pub:24190";

    public static String server(Context c) { return sp(c).getString("server", ""); }

    /** Profil perangkat: dibuat sekali, dipakai sebagai header X-Profile. */
    public static String profile(Context c) {
        String p = sp(c).getString("profile", "");
        if (p == null || p.isEmpty()) {
            p = "hp-" + Long.toHexString(System.currentTimeMillis() & 0xFFFFFFL);
            sp(c).edit().putString("profile", p).apply();
        }
        return p;
    }

    public static String token(Context c) { return sp(c).getString("token", ""); }

    public static boolean ready(Context c) { return !server(c).isEmpty() && !token(c).isEmpty(); }

    public static void save(Context c, String server, String token) {
        String s = server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.isEmpty() && !s.startsWith("http")) s = "http://" + s;
        sp(c).edit().putString("server", s).putString("token", token.trim()).apply();
    }
}
