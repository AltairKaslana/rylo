package com.example.ytmplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Player;

/** Foreground service: menjaga proses tetap hidup saat layar mati + notifikasi media + MediaSession. */
public class PlaybackService extends Service implements Playback.Listener {
    private static final String CH = "playback";
    private static final int ID = 1;
    private static final String A_TOGGLE = "ytm.toggle", A_NEXT = "ytm.next",
            A_PREV = "ytm.prev", A_STOP = "ytm.stop";

    private Playback pb;
    private MediaSessionCompat session;
    private NotificationManager nm;
    private Bitmap art;
    private String artFor;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        pb = Playback.get(this);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(CH, "Pemutaran", NotificationManager.IMPORTANCE_LOW));
        }
        // MediaSessionCompat menuntut MediaButtonReceiver, dan receiver itu mencari Service
        // ber-intent-filter MEDIA_BUTTON (sudah didaftarkan di manifest). Tanpa itu -> crash.
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                session = new MediaSessionCompat(this, "ytm");
            } else {
                session = new MediaSessionCompat(this, "ytm",
                        new ComponentName(this, androidx.media.session.MediaButtonReceiver.class), null);
            }
        } catch (Throwable t) {
            session = null;
        }
        if (session != null) {
            session.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { if (!pb.active()) pb.toggle(); }
                @Override public void onPause() { pb.player.pause(); }
                @Override public void onSkipToNext() { pb.next(); }
                @Override public void onSkipToPrevious() { pb.prev(); }
                @Override public void onSeekTo(long p) { pb.player.seekTo(p); }
                @Override public void onStop() { pb.stop(); }

                /** Semua tombol media (notifikasi, lockscreen, headset) masuk lewat sini. */
                @Override public boolean onMediaButtonEvent(Intent ev) {
                    KeyEvent k = ev == null ? null
                            : (KeyEvent) ev.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (k == null || k.getAction() != KeyEvent.ACTION_DOWN) return false;
                    Log2.trace(PlaybackService.this, "notif-key " + k.getKeyCode());
                    handleKey(k.getKeyCode());
                    return true;    // sudah ditangani, jangan diteruskan lagi (cegah dobel aksi)
                }
            });
            try { session.setActive(true); } catch (Throwable ignored) { }
        }
        pb.addListener(this);
    }

    /** Waktu aksi terakhir: satu ketukan tidak boleh menghasilkan dua aksi. */
    private long lastAct = 0;
    private int lastKey = -1;

    private void handleKey(int code) {
        long now = SystemClock.uptimeMillis();
        if (code == lastKey && now - lastAct < 700) {
            Log2.trace(this, "aksi ganda diabaikan key=" + code);
            return;
        }
        lastAct = now;
        lastKey = code;
        try {
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                    pb.toggle(); break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (!pb.active()) pb.toggle(); break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (pb.active()) pb.toggle(); break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    pb.next(); break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    pb.prev(); break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    pb.stop(); break;
                default: break;
            }
        } catch (Throwable t) {
            Log2.write(this, "handleKey " + code, t);
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (a != null && a.startsWith("ytm.act.")) a = a.substring("ytm.act.".length());
        try {
            if (Intent.ACTION_MEDIA_BUTTON.equals(a)) {
                KeyEvent ev = (KeyEvent) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    Log2.trace(this, "notif-key " + ev.getKeyCode());
                    handleKey(ev.getKeyCode());
                }
            }
            else if (A_TOGGLE.equals(a)) { Log2.trace(this, "notif-tombol toggle"); handleKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); }
            else if (A_NEXT.equals(a))   { Log2.trace(this, "notif-tombol next");   handleKey(KeyEvent.KEYCODE_MEDIA_NEXT); }
            else if (A_PREV.equals(a))   { Log2.trace(this, "notif-tombol prev");   handleKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); }
            else if (A_STOP.equals(a))   { Log2.trace(this, "notif-tombol stop");   handleKey(KeyEvent.KEYCODE_MEDIA_STOP); }
            else if ("ytm.refresh".equals(a)) { /* cukup segarkan notifikasi */ }
            else { Log2.trace(this, "action=" + a); }
            safeForeground();
        } catch (Throwable t) {
            // jangan biarkan aksi notifikasi/lockscreen mematikan aplikasi
            Log2.write(this, "ACTION " + a, t);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        pb.removeListener(this);
        if (session != null) session.release();
        super.onDestroy();
    }

    @Override public void onChange() {
        try { refresh(); } catch (Throwable ignored) { }
    }

    private void refreshNotif() {
        try { nm.notify(ID, build()); } catch (Throwable ignored) { }
    }

    private void refresh() {
        if (!pb.hasQueue()) {
            stopForeground(true);
            stopSelf();
            return;
        }
        try { loadArt(); } catch (Throwable ignored) { }
        try { updateSession(); } catch (Throwable ignored) { }
        safeForeground();
    }

    /** Notifikasi MediaStyle bisa bermasalah di ROM lama; kalau gagal, pakai notifikasi biasa
     *  supaya pemutaran tidak ikut mati. */
    private void safeForeground() {
        try {
            startForeground(ID, build());
        } catch (Throwable t) {
            try {
                Item it = pb.current();
                Notification n = new NotificationCompat.Builder(this, CH)
                        .setSmallIcon(R.drawable.ic_notif)
                        .setContentTitle(it != null ? it.title : "Memutar")
                        .setContentText(it != null ? it.subtitle : "")
                        .setOnlyAlertOnce(true)
                        .setContentIntent(PendingIntent.getActivity(this, 0,
                                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0))
                        .build();
                startForeground(ID, n);
            } catch (Throwable ignored) { }
        }
    }

    private PendingIntent act(String action) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent i = new Intent(this, ActionReceiver.class);
        i.setAction("ytm.act." + action);   // aksi harus unik agar tiap tombol punya PendingIntent sendiri
        i.putExtra("a", action);
        return PendingIntent.getBroadcast(this, action.hashCode(), i, f);
    }

    /**
     * Tombol notifikasi dikirim sebagai tombol media ke MediaSession.
     * Ini jalur yang SAMA dengan tombol lockscreen/headset yang sudah terbukti bekerja di
     * perangkat ini. PendingIntent broadcast biasa diabaikan sebagian ROM lama.
     */
    private PendingIntent mediaKey(int keyCode) {
        if (session == null) return act(A_TOGGLE);
        try {
            return androidx.media.session.MediaButtonReceiver
                    .buildMediaButtonPendingIntent(session, keyCode);
        } catch (Throwable t) {
            return act(keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ? A_NEXT : A_PREV);
        }
    }

    private Notification build() {
        Item it = pb.current();
        if (art != null && art.isRecycled()) art = null;
        boolean playing = pb.active();
        int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), f);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(it != null ? it.title : "Memuat...")
                .setContentText(it != null ? it.subtitle : "")
                .setLargeIcon(art)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_n_prev, "Sebelumnya",
                        mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                .addAction(playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play,
                        playing ? "Jeda" : "Putar", mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                .addAction(R.drawable.ic_n_next, "Berikutnya",
                        mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        if (session != null) {
            b.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(act(A_STOP)));
        }
        return b.build();
    }

    private void loadArt() {
        Item it = pb.current();
        final String u = it == null ? null : Api.img(this, it.thumb, 300);
        if (u == null || u.equals(artFor)) return;
        artFor = u;
        art = null;
        Glide.with(getApplicationContext()).asBitmap().load(u)
                .override(128, 128).format(DecodeFormat.PREFER_RGB_565)
                .into(new CustomTarget<Bitmap>(128, 128) {
            @Override public void onResourceReady(@NonNull Bitmap r, @Nullable Transition<? super Bitmap> t) {
                if (!u.equals(artFor)) return;
                art = r;
                try { nm.notify(ID, build()); } catch (Throwable ignored) { }
            }

            @Override public void onLoadCleared(@Nullable Drawable d) { }
        });
    }

    private void updateSession() {
        if (session == null) return;
        Item it = pb.current();
        if (it == null) return;
        long dur = pb.player.getDuration();
        if (dur == C.TIME_UNSET || dur <= 0) dur = it.dur * 1000L;
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, it.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it.subtitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur).build());
        int st = pb.player.isPlaying() ? PlaybackStateCompat.STATE_PLAYING
                : pb.player.getPlaybackState() == Player.STATE_BUFFERING ? PlaybackStateCompat.STATE_BUFFERING
                : PlaybackStateCompat.STATE_PAUSED;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(st, pb.player.getCurrentPosition(), 1f).build());
    }
}
