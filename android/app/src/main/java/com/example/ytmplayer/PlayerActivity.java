package com.example.ytmplayer;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Player;

import java.util.List;

public class PlayerActivity extends AppCompatActivity implements Playback.Listener {
    private Playback pb;
    private ImageView cover, play, shuffle, repeat;
    private TextView title, artist, pos, dur, lyricsText;
    private View lyricsScroll;
    private SeekBar seek;
    private boolean dragging, lyricsOn;
    private String shownId, lyricsFor;
    private int side;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            progress();
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        pb = Playback.get(this);
        cover = findViewById(R.id.p_cover);
        play = findViewById(R.id.p_play);
        shuffle = findViewById(R.id.p_shuffle);
        repeat = findViewById(R.id.p_repeat);
        title = findViewById(R.id.p_title);
        artist = findViewById(R.id.p_artist);
        pos = findViewById(R.id.p_pos);
        dur = findViewById(R.id.p_dur);
        seek = findViewById(R.id.p_seek);
        lyricsText = findViewById(R.id.lyrics_text);
        lyricsScroll = findViewById(R.id.lyrics_scroll);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        side = Math.min(dm.widthPixels - Ui.dp(this, 48), (int) (dm.heightPixels * 0.40f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Ui.dp(this, 8);
        findViewById(R.id.art_box).setLayoutParams(lp);

        findViewById(R.id.p_down).setOnClickListener(v -> finish());
        findViewById(R.id.p_queue).setOnClickListener(v -> showQueue());
        findViewById(R.id.p_lyrics).setOnClickListener(v -> toggleLyrics());
        play.setOnClickListener(v -> pb.toggle());
        findViewById(R.id.p_next).setOnClickListener(v -> pb.next());
        findViewById(R.id.p_prev).setOnClickListener(v -> pb.prev());
        shuffle.setOnClickListener(v -> pb.toggleShuffle());
        repeat.setOnClickListener(v -> pb.cycleRepeat());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                long d = pb.player.getDuration();
                if (fromUser && d > 0) pos.setText(Ui.fmt(d * p / 1000));
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { dragging = true; }

            @Override public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                long d = pb.player.getDuration();
                if (d > 0) pb.player.seekTo(d * sb.getProgress() / 1000);
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        pb.addListener(this);
        onChange();
        h.post(tick);
    }

    @Override protected void onStop() {
        pb.removeListener(this);
        h.removeCallbacks(tick);
        super.onStop();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.slide_down);
    }

    @Override public void onChange() {
        Item it = pb.current();
        if (it == null) { finish(); return; }
        title.setText(it.title);
        artist.setText(it.subtitle);
        if (!it.id.equals(shownId)) {
            shownId = it.id;
            Api.thumb(cover, it.thumb, side, 8);
            if (lyricsOn) loadLyrics(it);
        }
        play.setImageResource(pb.active() ? R.drawable.ic_pause : R.drawable.ic_play);
        shuffle.setAlpha(pb.player.getShuffleModeEnabled() ? 1f : 0.4f);
        int m = pb.player.getRepeatMode();
        repeat.setAlpha(m == Player.REPEAT_MODE_OFF ? 0.4f : 1f);
        if (m == Player.REPEAT_MODE_ONE) repeat.setColorFilter(Color.parseColor("#FF4E45"));
        else repeat.clearColorFilter();
    }

    private void progress() {
        long d = pb.player.getDuration();
        if (d == C.TIME_UNSET || d <= 0) return;
        dur.setText(Ui.fmt(d));
        if (!dragging) {
            long p = pb.player.getCurrentPosition();
            pos.setText(Ui.fmt(p));
            seek.setProgress((int) (1000L * p / d));
            seek.setSecondaryProgress((int) (1000L * pb.player.getBufferedPosition() / d));
        }
    }

    private void toggleLyrics() {
        lyricsOn = !lyricsOn;
        lyricsScroll.setVisibility(lyricsOn ? View.VISIBLE : View.GONE);
        Item it = pb.current();
        if (lyricsOn && it != null && !it.id.equals(lyricsFor)) loadLyrics(it);
    }

    private void loadLyrics(final Item it) {
        lyricsFor = it.id;
        lyricsText.setText("Memuat lirik…");
        Api.lyrics(this, it.id, new Api.Cb<Api.Lyrics>() {
            @Override public void ok(Api.Lyrics r) {
                if (!it.id.equals(lyricsFor)) return;
                lyricsText.setText(r.lyrics == null || r.lyrics.isEmpty() ? "Lirik tidak tersedia" : r.lyrics);
            }

            @Override public void err(String m) {
                if (it.id.equals(lyricsFor)) lyricsText.setText("Lirik tidak tersedia");
            }
        });
    }

    private void showQueue() {
        List<Item> q = pb.queue();
        String[] names = new String[q.size()];
        int cur = pb.player.getCurrentMediaItemIndex();
        for (int i = 0; i < names.length; i++) {
            names[i] = (i == cur ? "▶  " : "") + q.get(i).title + " — " + q.get(i).subtitle;
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Antrean")
                .setItems(names, (d, i) -> pb.jumpTo(i))
                .show();
    }
}
