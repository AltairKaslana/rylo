package com.example.ytmplayer;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pengendali pemutaran tunggal: ExoPlayer + antrean + autoplay (radio) + prefetch. */
public final class Playback {
    public interface Listener { void onChange(); }

    private static Playback inst;

    public static synchronized Playback get(Context c) {
        if (inst == null) inst = new Playback(c.getApplicationContext());
        return inst;
    }

    public final ExoPlayer player;
    private final Context app;
    private final List<Item> queue = new ArrayList<>();
    private final Set<String> have = new HashSet<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loadingRadio;
    private int errors;

    private Playback(Context app) {
        this.app = app;
        player = new ExoPlayer.Builder(app)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(20000, 60000, 2500, 5000)
                        .setPrioritizeTimeOverSizeThresholds(true).build())
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) { onTrack(); }
            @Override public void onPlaybackStateChanged(int state) { fire(); }
            @Override public void onIsPlayingChanged(boolean playing) { if (playing) errors = 0; fire(); }
            @Override public void onShuffleModeEnabledChanged(boolean on) { fire(); }
            @Override public void onRepeatModeChanged(int mode) { fire(); }

            @Override public void onPlayerError(PlaybackException e) {
                errors++;
                if (errors <= 3 && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                } else {
                    Toast.makeText(Playback.this.app, "Gagal memutar: " + e.getErrorCodeName(),
                            Toast.LENGTH_LONG).show();
                }
                fire();
            }
        });
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void fire() {
        for (Listener l : listeners) {
            try { l.onChange(); } catch (Throwable ignored) { }
        }
    }

    private MediaItem mediaItem(Item s) {
        return new MediaItem.Builder().setUri(Api.stream(app, s.id)).setMediaId(s.id).build();
    }

    /** Putar daftar mulai dari indeks tertentu (hanya item bertipe lagu yang masuk antrean). */
    public void play(List<Item> list, int startInList, boolean shuffle) {
        queue.clear();
        have.clear();
        List<MediaItem> mis = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < list.size(); i++) {
            Item s = list.get(i);
            if (!s.isSong()) continue;
            if (i == startInList) start = queue.size();
            queue.add(s);
            have.add(s.id);
            mis.add(mediaItem(s));
        }
        if (mis.isEmpty()) return;
        if (start < 0) start = 0;
        errors = 0;
        player.setShuffleModeEnabled(shuffle);
        player.setMediaItems(mis, start, C.TIME_UNSET);
        player.prepare();
        player.play();
        ContextCompat.startForegroundService(app, new Intent(app, PlaybackService.class));
        fire();
        main.post(this::onTrack);
    }

    private void onTrack() {
        Item cur = current();
        if (cur != null) Api.reportHistory(app, cur);
        fire();
        int n = player.getNextMediaItemIndex();
        if (n != C.INDEX_UNSET) Api.prefetch(app, player.getMediaItemAt(n).mediaId);
        maybeAutoplay();
    }

    private void maybeAutoplay() {
        if (loadingRadio || player.getShuffleModeEnabled()
                || player.getRepeatMode() != Player.REPEAT_MODE_OFF) return;
        int remaining = player.getMediaItemCount() - player.getCurrentMediaItemIndex() - 1;
        Item cur = current();
        if (cur == null || remaining > 1) return;
        loadingRadio = true;
        Api.radio(app, cur.id, new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                loadingRadio = false;
                if (r.items != null) append(r.items);
            }

            @Override public void err(String m) { loadingRadio = false; }
        });
    }

    private void append(List<Item> songs) {
        List<MediaItem> add = new ArrayList<>();
        for (Item s : songs) {
            if (!s.isSong() || have.contains(s.id)) continue;
            have.add(s.id);
            queue.add(s);
            add.add(mediaItem(s));
        }
        if (!add.isEmpty()) {
            player.addMediaItems(add);
            fire();
        }
    }

    public void toggle() {
        try { toggleInner(); } catch (Throwable t) { Log2.write(app, "toggle", t); }
    }

    private void toggleInner() {
        if (player.isPlaying()) { player.pause(); return; }
        int s = player.getPlaybackState();
        if (s == Player.STATE_IDLE) player.prepare();
        else if (s == Player.STATE_ENDED) player.seekToDefaultPosition(0);
        player.play();
    }

    public void next() {
        try { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); }
        catch (Throwable t) { Log2.write(app, "next", t); }
    }

    public void prev() {
        if (player.getCurrentPosition() > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0);
        else player.seekToPreviousMediaItem();
    }

    public void jumpTo(int index) {
        player.seekTo(index, C.TIME_UNSET);
        player.play();
    }

    public void toggleShuffle() { player.setShuffleModeEnabled(!player.getShuffleModeEnabled()); }

    public void cycleRepeat() {
        int m = player.getRepeatMode();
        player.setRepeatMode(m == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ALL
                : m == Player.REPEAT_MODE_ALL ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void stop() {
        player.stop();
        player.clearMediaItems();
        queue.clear();
        have.clear();
        fire();
    }

    public boolean hasQueue() { return !queue.isEmpty(); }

    public List<Item> queue() { return queue; }

    /** True jika sedang memutar atau sedang menyiapkan (buffering) tanpa dijeda. */
    public boolean active() {
        int s = player.getPlaybackState();
        return player.getPlayWhenReady() && (s == Player.STATE_READY || s == Player.STATE_BUFFERING);
    }

    public Item current() {
        int i = player.getCurrentMediaItemIndex();
        return i >= 0 && i < queue.size() ? queue.get(i) : null;
    }
}
