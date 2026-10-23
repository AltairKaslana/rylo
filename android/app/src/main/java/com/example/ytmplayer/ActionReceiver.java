package com.example.ytmplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Penerima aksi dari tombol notifikasi.
 *
 * Di KitKat, startService dari BroadcastReceiver saat aplikasi di background kadang diabaikan
 * oleh ROM: tombol tampak ditekan tetapi tidak terjadi apa pun. Karena itu aksi dieksekusi
 * langsung di sini lewat singleton Playback, lalu service hanya diminta memperbarui notifikasi.
 */
public class ActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String action = i == null ? null : i.getStringExtra("a");
        if (action == null && i != null) {
            // sebagian ROM mengirim aksi di field action, bukan di extra
            String a = i.getAction();
            if (a != null && a.startsWith("ytm.act.")) action = a.substring("ytm.act.".length());
        }
        if (action == null) return;

        try {
            Playback pb = Playback.get(c);
            if ("ytm.toggle".equals(action)) pb.toggle();
            else if ("ytm.next".equals(action)) pb.next();
            else if ("ytm.prev".equals(action)) pb.prev();
            else if ("ytm.stop".equals(action)) pb.stop();
            Log2.trace(c, "act-exec " + action);
        } catch (Throwable t) {
            Log2.write(c, "act-exec " + action, t);
        }

        // pastikan service hidup & notifikasi ikut berubah
        try {
            c.startService(new Intent(c, PlaybackService.class).setAction("ytm.refresh"));
        } catch (Throwable ignored) { }
    }
}
