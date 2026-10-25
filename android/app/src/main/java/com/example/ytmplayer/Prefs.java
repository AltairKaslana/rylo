package com.example.ytmplayer;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("ytm", Context.MODE_PRIVATE);
    }

    /** Alamat bawaan supaya tidak perlu mengetik panjang saat pertama pasang. */
    public static final String DEFAULT_SERVER = "https://musik.altairk.my.id";

    public static String server(Context c) { return sp(c).getString("server", ""); }

    /**
     * Profil perangkat: dibuat sekali, dipakai sebagai header X-Profile.
     *
     * PENTING: nilai ini menentukan data mana yang terlihat di server (favorit, playlist,
     * riwayat). Karena disimpan di SharedPreferences, menghapus aplikasi membuatnya baru
     * dan data lama tidak lagi terlihat - bukan hilang, hanya tidak terhubung. Karena itu
     * nilainya ditampilkan di dialog "Server relay" supaya bisa diketik ulang setelah
     * memasang ulang.
     */
    public static String profile(Context c) {
        String p = sp(c).getString("profile", "");
        if (p == null || p.isEmpty()) {
            p = "hp-" + Long.toHexString(System.currentTimeMillis() & 0xFFFFFFL);
            sp(c).edit().putString("profile", p).apply();
        }
        return p;
    }

    /**
     * Ganti profil perangkat (mis. memulihkan profil lama setelah memasang ulang).
     *
     * Aturan bersihnya sama dengan `library.clean_profile()` di server: hanya
     * [A-Za-z0-9_-], maksimal 40 karakter. Kalau dibiarkan berbeda, yang tersimpan di HP
     * tidak sama dengan yang dipakai server - favorit seolah hilang tanpa sebab yang jelas.
     */
    public static void setProfile(Context c, String p) {
        String v = p == null ? "" : p.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (v.length() > 40) v = v.substring(0, 40);
        if (!v.isEmpty()) sp(c).edit().putString("profile", v).apply();
    }

    public static String token(Context c) { return sp(c).getString("token", ""); }

    // ---- sesi akun (username + sandi per profil) ----

    /** Token sesi hasil login. Kosong = belum masuk. */
    public static String session(Context c) { return sp(c).getString("session", ""); }

    public static String username(Context c) { return sp(c).getString("username", ""); }

    /** Alias singkat profil, mis. "Altair". */
    public static String alias(Context c) {
        String a = sp(c).getString("alias", "");
        return (a == null || a.isEmpty()) ? username(c) : a;
    }

    public static boolean loggedIn(Context c) { return !session(c).isEmpty(); }

    /** Simpan hasil login. */
    public static void saveSession(Context c, String token, String username, String alias) {
        sp(c).edit().putString("session", token == null ? "" : token)
                .putString("username", username == null ? "" : username)
                .putString("alias", alias == null ? "" : alias).apply();
    }

    /** Hapus sesi (keluar). Alamat server & kunci master tetap disimpan. */
    public static void clearSession(Context c) {
        sp(c).edit().remove("session").remove("username").remove("alias").apply();
    }

    public static boolean ready(Context c) { return !server(c).isEmpty() && !token(c).isEmpty(); }

    /** True kalau perangkat ini perlu jalur TLS cadangan (sertifikat RSA kita sendiri). */
    public static boolean altTls(Context c) { return sp(c).getBoolean("alt_tls", false); }

    /** Port tunnel yang sudah terbukti berhasil (0 = belum tahu). */
    public static int borePort(Context c) { return sp(c).getInt("bore_port", 0); }

    public static void setBorePort(Context c, int port) { sp(c).edit().putInt("bore_port", port).apply(); }

    public static void setAltTls(Context c, boolean on) {
        sp(c).edit().putBoolean("alt_tls", on).apply();
    }

    public static void save(Context c, String server, String token) {
        String s = server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.isEmpty() && !s.startsWith("http")) s = "http://" + s;
        sp(c).edit().putString("server", s).putString("token", token.trim()).apply();
    }
}
