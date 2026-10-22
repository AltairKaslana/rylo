package com.example.ytmplayer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.exoplayer2.C;

import java.util.List;

public class MainActivity extends AppCompatActivity implements Playback.Listener {
    private Playback pb;
    private View mini;
    private ProgressBar miniProg;
    private ImageView miniCover, miniPlay;
    private TextView miniTitle, miniArtist;
    private final View[] tabs = new View[3];
    private int tab = -1;
    private String shownId;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            progress();
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pb = Playback.get(this);
        mini = findViewById(R.id.mini);
        miniProg = findViewById(R.id.mini_progress);
        miniCover = findViewById(R.id.mini_cover);
        miniPlay = findViewById(R.id.mini_play);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        tabs[0] = findViewById(R.id.tab_home);
        tabs[1] = findViewById(R.id.tab_search);
        tabs[2] = findViewById(R.id.tab_library);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            tabs[i].setOnClickListener(v -> select(k));
        }
        findViewById(R.id.btn_settings).setOnClickListener(v -> settings(false));
        findViewById(R.id.mini_row).setOnClickListener(v -> {
            startActivity(new Intent(this, PlayerActivity.class));
            overridePendingTransition(R.anim.slide_up, 0);
        });
        miniPlay.setOnClickListener(v -> pb.toggle());
        if (b == null) select(0);
        else { tab = b.getInt("tab", 0); highlight(); }
        if (!Prefs.ready(this)) settings(true);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
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

    private void select(int i) {
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        tab = i;
        Fragment f = i == 0 ? new HomeFragment() : i == 1 ? new SearchFragment() : new LibraryFragment();
        getSupportFragmentManager().beginTransaction().replace(R.id.content, f).commitAllowingStateLoss();
        highlight();
    }

    private void highlight() {
        for (int i = 0; i < 3; i++) tabs[i].setAlpha(i == tab ? 1f : 0.55f);
    }

    /** Dipanggil fragment: lagu -> putar dengan daftar sebagai antrean; lainnya -> buka halaman detail. */
    public void open(Item it, List<Item> ctx) {
        if (it.isSong()) {
            int i = ctx.indexOf(it);
            pb.play(ctx, Math.max(i, 0), false);
        } else {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content, DetailFragment.make(it)).addToBackStack(null).commit();
        }
    }

    @Override public void onChange() {
        Item it = pb.current();
        mini.setVisibility(it == null ? View.GONE : View.VISIBLE);
        if (it != null) {
            miniTitle.setText(it.title);
            miniArtist.setText(it.subtitle);
            if (!it.id.equals(shownId)) {
                shownId = it.id;
                Api.thumb(miniCover, it.thumb, Ui.dp(this, 40), 4);
            }
        }
        miniPlay.setImageResource(pb.active() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void progress() {
        long d = pb.player.getDuration();
        if (d != C.TIME_UNSET && d > 0) miniProg.setProgress((int) (1000L * pb.player.getCurrentPosition() / d));
    }

    private void settings(boolean first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);

        TextView info = new TextView(this);
        info.setText("Alamat server dan PIN ada di server (file /opt/ytm-relay/env).");
        info.setTextSize(12);
        info.setTextColor(0xFF9AA0A6);
        info.setPadding(0, 0, 0, Ui.dp(this, 10));
        box.addView(info);

        final EditText s = field("http://192.168.1.9:8000", Prefs.server(this));
        final EditText t = field("PIN (misal 4821)", Prefs.token(this));
        t.setInputType(InputType.TYPE_CLASS_NUMBER);
        t.setTextSize(22);
        t.setGravity(android.view.Gravity.CENTER);
        box.addView(s);
        box.addView(t);

        final TextView status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF9AA0A6);
        status.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(status);

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Server relay")
                .setView(box)
                .setCancelable(!first)
                .setPositiveButton("Simpan", (d, w) -> {
                    String srv = s.getText().toString().trim();
                    String pin = t.getText().toString().trim();
                    if (srv.isEmpty() || pin.isEmpty()) {
                        toast("Alamat dan PIN belum lengkap");
                        return;
                    }
                    Prefs.save(this, srv, pin);
                    Api.clearCache();
                    Api.ping(this, new Api.Cb<String>() {
                        @Override public void ok(String r) { toast(r); select(tab < 0 ? 0 : tab); }
                        @Override public void err(String m) { toast(m); }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Menu tekan-lama pada lagu: favorit & playlist. */
    public void trackMenu(final Item it, final String mode, final String pid, final Runnable after) {
        final boolean inPl = "pl".equals(mode);
        final String[] ops = inPl
                ? new String[]{"Tambah ke favorit", "Tambah ke playlist lain", "Hapus dari playlist ini"}
                : new String[]{"Tambah ke favorit", "Tambah ke playlist"};
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(it.title)
                .setItems(ops, (d, i) -> {
                    if (i == 0) {
                        Api.favAdd(this, it, new Api.Cb<Api.Ok>() {
                            @Override public void ok(Api.Ok r) { toast("Ditambahkan ke favorit"); if (after != null) after.run(); }
                            @Override public void err(String m) { toast(m); }
                        });
                    } else if (i == 1) {
                        pickPlaylist(it, after);
                    } else {
                        Api.removeFromPlaylist(this, pid, it.id, new Api.Cb<Api.Ok>() {
                            @Override public void ok(Api.Ok r) { toast("Dihapus dari playlist"); if (after != null) after.run(); }
                            @Override public void err(String m) { toast(m); }
                        });
                    }
                })
                .show();
    }

    private void pickPlaylist(final Item it, final Runnable after) {
        Api.playlists(this, new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                final List<Item> pls = r.items != null ? r.items : java.util.Collections.<Item>emptyList();
                final String[] names = new String[pls.size() + 1];
                names[0] = "+ Buat playlist baru";
                for (int i = 0; i < pls.size(); i++) names[i + 1] = pls.get(i).title;
                new AlertDialog.Builder(MainActivity.this, R.style.DialogTheme)
                        .setTitle("Simpan ke playlist")
                        .setItems(names, (d, i) -> {
                            if (i == 0) newPlaylistWith(it, after);
                            else addTo(pls.get(i - 1).id, it, after);
                        })
                        .show();
            }

            @Override public void err(String m) { toast(m); }
        });
    }

    private void newPlaylistWith(final Item it, final Runnable after) {
        final EditText e = new EditText(this);
        e.setHint("Nama playlist");
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Playlist baru")
                .setView(e)
                .setPositiveButton("Buat & simpan", (d, w) -> {
                    String name = e.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Api.createPlaylist(this, name, new Api.Cb<Item>() {
                        @Override public void ok(Item pl) { addTo(pl.id, it, after); }
                        @Override public void err(String m) { toast(m); }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void addTo(String pid, Item it, final Runnable after) {
        Api.addToPlaylist(this, pid, it, new Api.Cb<Api.Ok>() {
            @Override public void ok(Api.Ok r) { toast("Disimpan ke playlist"); if (after != null) after.run(); }
            @Override public void err(String m) { toast(m); }
        });
    }

    public void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine();
        e.setTextColor(0xFFFFFFFF);
        return e;
    }
}
