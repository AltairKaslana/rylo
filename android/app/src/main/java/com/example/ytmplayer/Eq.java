package com.example.ytmplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * Equalizer untuk pemutaran Rylo.
 *
 * Kenapa di kelas sendiri: efek audio menempel pada SESI AUDIO, bukan pada
 * aplikasi. ExoPlayer membuat sesi baru setiap kali dibuat ulang (mis. setelah
 * proses dibunuh Android karena RAM di HP 512 MB), jadi efek lama akan hilang
 * tanpa jejak. Semua pemasangan lewat satu tempat supaya tidak ada jalur yang
 * lupa memasang ulang.
 *
 * Batasan yang perlu diketahui: {@code Equalizer} bawaan hanya 5 band dan mutu
 * band-nya tergantung vendor ROM. BassBoost juga tidak selalu ada. Semua
 * kegagalan ditelan dan dicatat, karena efek audio yang gagal TIDAK boleh
 * membuat musik ikut gagal - lebih baik tanpa equalizer daripada tanpa suara.
 *
 * Pengaturan disimpan per perangkat di SharedPreferences, bukan di server:
 * suara tiap HP berbeda (speaker kecil vs earbud), jadi ikut profil akun justru
 * salah.
 */
public final class Eq {
    private static final String TAG = "RyloEq";

    /** Nama preset, urutannya dipakai UI. "Custom" selalu terakhir. */
    public static final String[] PRESETS = {
            "Flat", "Bass", "Vokal", "Rock", "Elektronik", "Custom"
    };

    /**
     * Nilai tiap band dalam milibel, urutan: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz.
     * Angka sengaja konservatif (+-6 dB): di speaker HP, boost lebih dari itu
     * langsung pecah, dan telinga di earbud murah sudah terdengar berubah.
     */
    private static final short[][] PRESET_GAINS = {
            {   0,    0,    0,    0,    0 },   // Flat
            { 600,  350,    0,    0, -100 },   // Bass
            {-200,  100,  400,  300,    0 },   // Vokal
            { 400,  150,  -50,  250,  250 },   // Rock
            { 500,  100,    0,  150,  300 },   // Elektronik
            {   0,    0,    0,    0,    0 },   // Custom (diisi slider)
    };

    /** Batas slider custom, supaya tidak melebihi rentang yang aman. */
    public static final short CUSTOM_MIN = -600;
    public static final short CUSTOM_MAX = 600;

    private static Equalizer eq;
    private static BassBoost bass;
    private static int sessionId = 0;

    private Eq() {}

    // ---------------- penyimpanan ----------------

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("ytm", Context.MODE_PRIVATE);
    }

    /** Indeks preset aktif (0..PRESETS.length-1), bawaan Flat. */
    public static int presetIndex(Context c) {
        return Math.max(0, Math.min(PRESETS.length - 1, sp(c).getInt("eq_preset", 0)));
    }

    public static void setPresetIndex(Context c, int idx) {
        sp(c).edit().putInt("eq_preset", idx).apply();
    }

    /** true kalau equalizer diaktifkan pengguna. */
    public static boolean enabled(Context c) {
        return sp(c).getBoolean("eq_on", false);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("eq_on", on).apply();
    }

    /** Nilai band untuk preset Custom, disimpan sebagai CSV 5 angka milibel. */
    public static short[] customGains(Context c) {
        String raw = sp(c).getString("eq_custom", "");
        short[] out = new short[5];
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(",");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try { out[i] = (short) Math.max(CUSTOM_MIN, Math.min(CUSTOM_MAX, Integer.parseInt(parts[i].trim()))); }
            catch (Throwable ignored) { out[i] = 0; }
        }
        return out;
    }

    public static void setCustomGains(Context c, short[] gains) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append(',');
            sb.append(i < gains.length ? gains[i] : 0);
        }
        sp(c).edit().putString("eq_custom", sb.toString()).apply();
    }

    /** Gain yang harus dipakai sekarang: preset terpilih, atau custom kalau dipilih. */
    public static short[] activeGains(Context c) {
        int idx = presetIndex(c);
        if (idx >= 0 && idx < PRESETS.length - 1) return PRESET_GAINS[idx].clone();
        return customGains(c);
    }

    /** BassBoost tambahan dalam persen 0..100. 0 = mati. */
    public static int bassStrength(Context c) {
        return Math.max(0, Math.min(100, sp(c).getInt("eq_bass", 0)));
    }

    public static void setBassStrength(Context c, int pct) {
        sp(c).edit().putInt("eq_bass", Math.max(0, Math.min(100, pct))).apply();
    }

    // ---------------- pemasangan ke sesi audio ----------------

    /**
     * Pasang (atau pasang ulang) efek pada sesi audio ExoPlayer.
     *
     * Dipanggil setiap kali pemutar siap dan setiap kali pengaturan berubah.
     * Kalau sesi berubah, efek lama dilepas dulu - dua efek pada dua sesi
     * sekaligus menahan resource tanpa manfaat.
     */
    public static synchronized void attach(Context c, int audioSession) {
        if (audioSession == 0) return;
        if (audioSession == sessionId && eq != null) {
            apply(c);
            return;
        }
        release();
        sessionId = audioSession;
        try {
            eq = new Equalizer(0, audioSession);
            eq.setEnabled(true);
        } catch (Throwable t) {
            Log.w(TAG, "equalizer tidak tersedia: " + t.getClass().getSimpleName());
            eq = null;
        }
        try {
            bass = new BassBoost(0, audioSession);
        } catch (Throwable t) {
            Log.w(TAG, "bassboost tidak tersedia: " + t.getClass().getSimpleName());
            bass = null;
        }
        apply(c);
    }

    /** Terapkan pengaturan tersimpan ke efek yang sedang terpasang. */
    public static synchronized void apply(Context c) {
        boolean on = enabled(c);
        if (eq != null) {
            try {
                eq.setEnabled(on);
                if (on) {
                    short[] want = activeGains(c);
                    short min = eq.getBandLevelRange()[0];
                    short max = eq.getBandLevelRange()[1];
                    short bands = eq.getNumberOfBands();
                    for (short b = 0; b < bands && b < want.length; b++) {
                        short level = (short) Math.max(min, Math.min(max, want[b]));
                        eq.setBandLevel(b, level);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "gagal menerapkan equalizer: " + t.getClass().getSimpleName());
            }
        }
        if (bass != null) {
            try {
                int pct = on ? bassStrength(c) : 0;
                bass.setEnabled(pct > 0);
                if (pct > 0) bass.setStrength((short) (pct * 10));
            } catch (Throwable t) {
                Log.w(TAG, "gagal menerapkan bassboost: " + t.getClass().getSimpleName());
            }
        }
    }

    /** Frekuensi tengah tiap band (Hz) untuk label UI; 0 kalau efek tidak ada. */
    public static synchronized int[] bandFrequencies() {
        if (eq == null) return new int[]{60, 230, 910, 3600, 14000};
        try {
            short n = eq.getNumberOfBands();
            int[] hz = new int[n];
            for (short b = 0; b < n; b++) hz[b] = eq.getCenterFreq(b) / 1000;
            return hz;
        } catch (Throwable ignored) {
            return new int[]{60, 230, 910, 3600, 14000};
        }
    }

    /** Rentang level yang didukung perangkat, milibel. */
    public static synchronized short[] levelRange() {
        try {
            if (eq != null) return eq.getBandLevelRange();
        } catch (Throwable ignored) { }
        return new short[]{CUSTOM_MIN, CUSTOM_MAX};
    }

    public static synchronized boolean hasBassBoost() {
        return bass != null;
    }

    public static synchronized void release() {
        try { if (eq != null) eq.release(); } catch (Throwable ignored) { }
        try { if (bass != null) bass.release(); } catch (Throwable ignored) { }
        eq = null;
        bass = null;
        sessionId = 0;
    }
}
