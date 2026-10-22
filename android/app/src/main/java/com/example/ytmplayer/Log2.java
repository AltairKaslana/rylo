package com.example.ytmplayer;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Pencatat kesalahan jaringan: satu file kecil di penyimpanan aplikasi supaya bisa dilaporkan. */
public final class Log2 {
    private Log2() {}

    /** Catat jejak ringan (aksi notifikasi dsb) tanpa mengganggu. */
    public static void trace(Context c, String line) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            if (dir == null) return;
            dir.mkdirs();
            File f = new File(dir, "netlog.txt");
            if (f.length() > 64 * 1024) {
                File old = new File(dir, "netlog.old.txt");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileWriter w = new FileWriter(f, true);
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " | " + line + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    /** Tulis ringkasan error, kembalikan teks pendek untuk ditampilkan di layar. */
    public static String write(Context c, String what, Throwable e) {
        String t = e == null ? "error" : e.getClass().getSimpleName();
        String m = e == null ? "" : String.valueOf(e.getMessage());
        String shortMsg = t + (m.isEmpty() || "null".equals(m) ? "" : ": " + m);
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            if (dir != null) {
                dir.mkdirs();
                FileWriter w = new FileWriter(new File(dir, "netlog.txt"), true);
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + " | " + what + "\n    " + shortMsg + "\n");
                w.close();
            }
        } catch (Throwable ignored) { }
        if (shortMsg.length() > 140) shortMsg = shortMsg.substring(0, 140) + "...";
        return shortMsg;
    }
}
