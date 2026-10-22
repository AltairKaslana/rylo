package com.example.ytmplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Penerima aksi dari tombol notifikasi.
 *
 * Di KitKat, PendingIntent.getService dari RemoteViews notifikasi kadang tidak dieksekusi
 * (tombol tampil tapi diam). Broadcast lebih andal di ROM lama: receiver ini meneruskan
 * aksinya ke PlaybackService lewat startService.
 */
public class ActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String action = i == null ? null : i.getStringExtra("a");
        if (action == null) return;
        try {
            Intent svc = new Intent(c, PlaybackService.class).setAction(action);
            c.startService(svc);
        } catch (Throwable t) {
            Log2.write(c, "broadcast " + action, t);
        }
    }
}
