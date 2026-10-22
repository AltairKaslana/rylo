package com.example.ytmplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Halaman album / playlist / artis: header + daftar lagu. */
public class DetailFragment extends BaseFragment {
    public static DetailFragment make(Item it) {
        DetailFragment f = new DetailFragment();
        Bundle a = new Bundle();
        a.putString("kind", it.kind);
        a.putString("id", it.id);
        a.putString("title", it.title);
        a.putString("thumb", it.thumb);
        f.setArguments(a);
        return f;
    }

    private Adapters.Rows rows;
    private View header;
    private List<Item> tracks = new ArrayList<>();

    @Override protected void init(View v) {
        Bundle a = getArguments();
        header = LayoutInflater.from(requireContext()).inflate(R.layout.header_detail, list, false);
        ((TextView) header.findViewById(R.id.h_title)).setText(a.getString("title"));
        setCover(a.getString("thumb"));
        header.findViewById(R.id.h_play).setOnClickListener(x -> {
            if (!tracks.isEmpty()) Playback.get(requireContext()).play(tracks, 0, false);
        });
        header.findViewById(R.id.h_shuffle).setOnClickListener(x -> {
            if (!tracks.isEmpty())
                Playback.get(requireContext()).play(tracks, new Random().nextInt(tracks.size()), true);
        });
        rows = new Adapters.Rows((it, ctx) -> main().open(it, ctx));
        rows.setLongPick((it, ctx) -> main().trackMenu(it, "", "", null));
        rows.setHeader(header);
        list.setAdapter(rows);
        load();
    }

    private void setCover(String thumb) {
        Api.thumb((ImageView) header.findViewById(R.id.h_cover), thumb, Ui.dp(requireContext(), 200), 8);
    }

    private void load() {
        Bundle a = getArguments();
        busy(true);
        Api.detail(requireContext(), a.getString("kind"), a.getString("id"), new Api.Cb<Api.Detail>() {
            @Override public void ok(Api.Detail d) {
                if (!isAdded()) return;
                busy(false);
                tracks = d.tracks != null ? d.tracks : new ArrayList<Item>();
                ((TextView) header.findViewById(R.id.h_title)).setText(d.title);
                ((TextView) header.findViewById(R.id.h_sub)).setText(d.subtitle);
                if (d.thumb != null && !d.thumb.isEmpty()) setCover(d.thumb);
                rows.set(tracks);
                if (tracks.isEmpty()) message("Tidak ada lagu");
            }

            @Override public void err(String m) { if (isAdded()) fail(m, DetailFragment.this::load); }
        });
    }
}
