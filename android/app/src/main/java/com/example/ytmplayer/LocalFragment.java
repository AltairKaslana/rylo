package com.example.ytmplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Daftar lagu dari koleksi lokal: favorit, riwayat, atau isi playlist sendiri. */
public class LocalFragment extends BaseFragment {
    public static final String MODE_FAV = "fav";
    public static final String MODE_HIST = "hist";
    public static final String MODE_PL = "pl";

    public static LocalFragment make(String mode, String id, String title) {
        LocalFragment f = new LocalFragment();
        Bundle a = new Bundle();
        a.putString("mode", mode);
        a.putString("id", id);
        a.putString("title", title);
        f.setArguments(a);
        return f;
    }

    private Adapters.Rows rows;
    private View header;
    private List<Item> tracks = new ArrayList<>();

    @Override protected void init(View v) {
        Bundle a = getArguments();
        header = LayoutInflater.from(requireContext()).inflate(R.layout.header_detail, list, false);
        ((TextView) header.findViewById(R.id.h_title)).setText(a.getString("title", "Koleksi"));
        ((TextView) header.findViewById(R.id.h_sub)).setText("koleksi lokal");
        Api.thumb((android.widget.ImageView) header.findViewById(R.id.h_cover), "", Ui.dp(requireContext(), 200), 8);
        header.findViewById(R.id.h_play).setOnClickListener(x -> play(0, false));
        header.findViewById(R.id.h_shuffle).setOnClickListener(x -> play(0, true));

        rows = new Adapters.Rows((it, ctx) -> play(ctx.indexOf(it), false));
        rows.setLongPick((it, ctx) -> main().trackMenu(it, a.getString("mode"), a.getString("id"), this::load));
        rows.setHeader(header);
        list.setAdapter(rows);
        load();
    }

    private void play(int start, boolean shuffle) {
        if (!tracks.isEmpty()) {
            Playback.get(requireContext()).play(tracks, Math.max(0, start), shuffle);
        }
    }

    void load() {
        Bundle a = getArguments();
        String mode = a.getString("mode", MODE_FAV);
        busy(true);
        if (MODE_PL.equals(mode)) {
            Api.playlistTracks(requireContext(), a.getString("id", ""), new Api.Cb<Api.Detail>() {
                @Override public void ok(Api.Detail d) {
                    if (!isAdded()) return;
                    busy(false);
                    apply(d.tracks, d.title);
                }

                @Override public void err(String m) { if (isAdded()) fail(m, LocalFragment.this::load); }
            });
        } else {
            Api.Cb<Api.Items> cb = new Api.Cb<Api.Items>() {
                @Override public void ok(Api.Items r) {
                    if (!isAdded()) return;
                    busy(false);
                    apply(r.items, null);
                }

                @Override public void err(String m) { if (isAdded()) fail(m, LocalFragment.this::load); }
            };
            if (MODE_HIST.equals(mode)) Api.history(requireContext(), cb);
            else Api.favs(requireContext(), cb);
        }
    }

    private void apply(List<Item> items, String title) {
        tracks = items != null ? items : new ArrayList<Item>();
        if (title != null && !title.isEmpty()) {
            ((TextView) header.findViewById(R.id.h_title)).setText(title);
        }
        rows.set(tracks);
        if (tracks.isEmpty()) message("Masih kosong - tandai lagu sebagai favorit dulu");
        else busy(false);
    }
}
