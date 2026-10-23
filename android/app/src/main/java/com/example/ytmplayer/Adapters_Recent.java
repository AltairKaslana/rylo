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
 * Daftar pencarian terakhir, dua tingkat:
 *  - tingkat pencarian: judul pencarian + jumlah lagu tersimpan
 *  - tingkat lagu: lagu-lagu yang pernah dipilih dari pencarian itu
 */
public class Adapters_Recent {
    public interface OnPickQuery { void pick(Api.Recent r); }
    public interface OnDropQuery { void drop(Api.Recent r); }
    public interface OnPickTrack { void pick(Api.Recent r, Item it); }
    public interface OnDropTrack { void drop(Api.Recent r, Item it); }

    /** Waktu relatif singkat. */
    static String relatif(long at) {
        if (at <= 0) return "";
        long detik = (System.currentTimeMillis() / 1000L) - at;
        if (detik < 60) return "baru saja";
        if (detik < 3600) return (detik / 60) + " menit lalu";
        if (detik < 86400) return (detik / 3600) + " jam lalu";
        if (detik < 2592000) return (detik / 86400) + " hari lalu";
        return "lama";
    }

    // ---------- tingkat pencarian ----------
    public static class QueryVH extends RecyclerView.ViewHolder {
        final TextView title, meta;
        final ImageView del;
        QueryVH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            del = v.findViewById(R.id.del);
        }
    }

    public static class QueryAdapter extends RecyclerView.Adapter<QueryVH> {
        private final List<Api.Recent> data;
        private final OnPickQuery pick;
        private final OnDropQuery drop;

        public QueryAdapter(List<Api.Recent> data, OnPickQuery pick, OnDropQuery drop) {
            this.data = data;
            this.pick = pick;
            this.drop = drop;
        }

        @Override public int getItemCount() { return data == null ? 0 : data.size(); }

        @NonNull @Override
        public QueryVH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new QueryVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull QueryVH h, int p) {
            final Api.Recent r = data.get(p);
            h.title.setText(r.query);
            int n = r.items == null ? 0 : r.items.size();
            String meta = n > 1 ? n + " lagu tersimpan"
                    : n == 1 ? "1 lagu tersimpan"
                    : relatif(r.at);
            h.meta.setText(meta);
            h.itemView.setOnClickListener(x -> pick.pick(r));
            h.del.setOnClickListener(x -> drop.drop(r));
        }
    }

    // ---------- tingkat lagu ----------
    public static class TrackVH extends RecyclerView.ViewHolder {
        final TextView title, meta;
        final ImageView del;
        TrackVH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            del = v.findViewById(R.id.del);
        }
    }

    public static class TrackAdapter extends RecyclerView.Adapter<TrackVH> {
        private final Api.Recent recent;
        private final List<Item> data;
        private final OnPickTrack pick;
        private final OnDropTrack drop;

        public TrackAdapter(Api.Recent recent, OnPickTrack pick, OnDropTrack drop) {
            this.recent = recent;
            this.data = recent.items;
            this.pick = pick;
            this.drop = drop;
        }

        @Override public int getItemCount() { return data == null ? 0 : data.size(); }

        @NonNull @Override
        public TrackVH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new TrackVH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull TrackVH h, int p) {
            final Item it = data.get(p);
            h.title.setText(it.title != null ? it.title : recent.query);
            h.meta.setText(it.subtitle != null ? it.subtitle : "");
            h.itemView.setOnClickListener(x -> pick.pick(recent, it));
            h.del.setOnClickListener(x -> drop.drop(recent, it));
        }
    }
}
