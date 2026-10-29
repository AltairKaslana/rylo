package com.example.ytmplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Daftar lagu yang pernah dipilih dari pencarian.
 *
 * Dulu dua tingkat: daftar kata kunci dulu, lalu lagu-lagunya setelah salah
 * satunya diketuk. Itu dua langkah untuk hal yang pengguna sebenarnya mau -
 * memutar lagu yang sudah pernah dicari. Sekarang rata: setiap baris langsung
 * lagu, dengan thumbnail, dan ketuk untuk memutar.
 */
public class Adapters_Recent {

    public static class TrackVH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, meta;
        final ImageView del;
        TrackVH(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            del = v.findViewById(R.id.del);
        }
    }

    public static class SongAdapter extends RecyclerView.Adapter<TrackVH> {
        public interface OnPlay { void play(Api.RecentSong s); }
        public interface OnDrop { void drop(Api.RecentSong s); }

        private final List<Api.RecentSong> data;
        private final OnPlay play;
        private final OnDrop drop;

        public SongAdapter(List<Api.RecentSong> data, OnPlay play, OnDrop drop) {
            this.data = data;
            this.play = play;
            this.drop = drop;
        }

        @Override public int getItemCount() { return data == null ? 0 : data.size(); }

        @NonNull @Override
        public TrackVH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new TrackVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull TrackVH h, int p) {
            final Api.RecentSong s = data.get(p);
            // kalau judul lagu hilang, pakai kata kunci pencarian asalnya
            h.title.setText(s.title != null ? s.title : s.query);
            h.meta.setText(s.subtitle != null ? s.subtitle : "");
            Api.thumb(h.cover, s.thumb, Ui.dp(h.itemView.getContext(), 48), 6);
            h.itemView.setOnClickListener(x -> play.play(s));
            h.del.setOnClickListener(x -> drop.drop(s));
        }
    }
}
