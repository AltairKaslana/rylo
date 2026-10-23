package com.example.ytmplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Daftar pencarian terakhir: judul, waktu relatif, tombol hapus per baris. */
public class Adapters_Recent {
    public interface OnPick { void pick(Api.Recent r); }
    public interface OnDrop { void drop(Api.Recent r); }

    public static class VH extends RecyclerView.ViewHolder {
        final TextView title, meta;
        final ImageView del;
        VH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            del = v.findViewById(R.id.del);
        }
    }

    public static class RecentAdapter extends RecyclerView.Adapter<VH> {
        private final List<Api.Recent> data;
        private final OnPick pick;
        private final OnDrop drop;

        public RecentAdapter(List<Api.Recent> data, OnPick pick, OnDrop drop) {
            this.data = data;
            this.pick = pick;
            this.drop = drop;
        }

        @Override public int getItemCount() { return data == null ? 0 : data.size(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            final Api.Recent r = data.get(p);
            h.title.setText(r.query);
            // kalau ada lagu tersimpan, tunjukukkan itu (yang akan langsung diputar)
            String meta = r.hasTrack() && r.title != null && !r.title.isEmpty()
                    ? r.title + (r.subtitle != null && !r.subtitle.isEmpty() ? " • " + r.subtitle : "")
                    : relatif(r.at);
            h.meta.setText(meta);
            h.itemView.setOnClickListener(x -> pick.pick(r));
            h.del.setOnClickListener(x -> drop.drop(r));
        }
    }

    /** Waktu relatif singkat: "baru saja", "5 mnt lalu", "2 jam lalu", "3 hari lalu". */
    static String relatif(long at) {
        if (at <= 0) return "";
        long detik = (System.currentTimeMillis() / 1000L) - at;
        if (detik < 60) return "baru saja";
        if (detik < 3600) return (detik / 60) + " menit lalu";
        if (detik < 86400) return (detik / 3600) + " jam lalu";
        if (detik < 2592000) return (detik / 86400) + " hari lalu";
        return "lama";
    }
}
