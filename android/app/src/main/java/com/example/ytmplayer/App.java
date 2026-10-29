package com.example.ytmplayer;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.multidex.MultiDexApplication;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Application + penangkap crash: jejak disimpan ke file supaya bisa dilaporkan. */
public class App extends MultiDexApplication {
    private static final String TAG = "YTMPlayer";

    @Override public void onCreate() {
        super.onCreate();
        Net.install(this);   // root CA Let's Encrypt + TLS 1.2 untuk KitKat
        Api.attach(this);
        Log2.trace(this, "TLS: " + Net.provider()
                + (Net.lastError().isEmpty() ? "" : " | err=" + Net.lastError()));

        // Cari port tunnel yang hidup SEKALI di awal, di thread terpisah.
        //
        // Tanpa ini aplikasi hanya mengenal port dari kegagalan: ia baru menyentuh
        // port yang benar SETELAH beberapa permintaan gagal lebih dulu. Terbukti di
        // netlog 29 Okt - 22 detik jatuh-bangun melalui port mati sebelum "recs ok",
        // dan "playback error -> port 24191" lima kali dalam empat hari karena jalur
        // audio dapat port yang sudah tidak ada.
        //
        // Sengaja tidak menyentuh Prefs.altTls: mengetahui port bukan berarti
        // memaksa jalur cadangan. Kalau domain Cloudflare sehat, ia tetap dipakai.
        final Context app = this;
        new Thread(new Runnable() {
            @Override public void run() {
                int p = Api.probePort(app);
                if (p > 0) Log2.trace(app, "port awal -> " + p);
            }
        }).start();

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try { dump(App.this, t, e); } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static void dump(Context c, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Runtime rt = Runtime.getRuntime();
        pw.println("waktu    : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("perangkat: " + Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE);
        pw.println("thread   : " + t.getName());
        pw.println("memori   : max=" + (rt.maxMemory() / 1048576) + "MB dipakai="
                + ((rt.totalMemory() - rt.freeMemory()) / 1048576) + "MB");
        pw.println();
        e.printStackTrace(pw);
        pw.flush();
        String s = sw.toString();
        Log.e(TAG, s);
        File[] dirs = {c.getExternalFilesDir(null), c.getFilesDir()};
        for (File dir : dirs) {
            if (dir == null) continue;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                FileWriter w = new FileWriter(new File(dir, "crash.txt"), false);
                w.write(s);
                w.close();
            } catch (Throwable ignored) { }
        }
    }
}
