package com.example.ytmplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class Adapters {
    private Adapters() {}

    /** it = item yang diketuk, ctx = daftar tempat item itu berada (untuk membentuk antrean). */
    public interface OnPick { void pick(Item it, List<Item> ctx); }

    /** Tekan lama pada baris (menu favorit / playlist). */
    public interface OnLongPick { void longPick(Item it, List<Item> ctx); }

    private static int radius(Item it) { return "artist".equals(it.kind) ? 999 : 4; }

    // ---------- Baris vertikal (hasil cari, isi playlist, library) ----------
    static class RowVH extends RecyclerView.ViewHolder {
        final ImageView cover; final TextView title, sub, dur;
        RowVH(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
            dur = v.findViewById(R.id.dur);
        }
    }

    public static class Rows extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Item> data = new ArrayList<>();
        private final OnPick cb;
        private OnLongPick longCb;
        private View header;

        public Rows(OnPick cb) { this.cb = cb; }

        public void setLongPick(OnLongPick l) { this.longCb = l; }

        public void set(List<Item> items) {
            data.clear();
            if (items != null) data.addAll(items);
            notifyDataSetChanged();
        }

        public void setHeader(View h) { header = h; notifyDataSetChanged(); }

        private int off() { return header == null ? 0 : 1; }

        @Override public int getItemCount() { return data.size() + off(); }

        @Override public int getItemViewType(int p) { return (header != null && p == 0) ? 0 : 1; }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == 0) {
                if (header.getParent() != null) ((ViewGroup) header.getParent()).removeView(header);
                return new RecyclerView.ViewHolder(header) { };
            }
            return new RowVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            if (!(h instanceof RowVH)) return;
            final Item it = data.get(p - off());
            RowVH v = (RowVH) h;
            v.title.setText(it.title);
            v.sub.setText(it.subtitle);
            v.dur.setText(it.isSong() && it.dur > 0 ? Ui.fmt(it.dur * 1000L) : "");
            Api.thumb(v.cover, it.thumb, Ui.dp(v.cover.getContext(), 48), radius(it) == 999 ? 24 : 4);
            v.itemView.setOnClickListener(x -> cb.pick(it, data));
            if (longCb != null) {
                v.itemView.setOnLongClickListener(x -> { longCb.longPick(it, data); return true; });
            }
        }
    }

    // ---------- Kartu horizontal (carousel beranda) ----------
    static class CardVH extends RecyclerView.ViewHolder {
        final ImageView cover; final TextView title, sub;
        CardVH(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
        }
    }

    public static class Cards extends RecyclerView.Adapter<CardVH> {
        private final List<Item> data; private final OnPick cb; private final OnLongPick longCb;

        public Cards(List<Item> data, OnPick cb) { this(data, cb, null); }

        public Cards(List<Item> data, OnPick cb, OnLongPick longCb) {
            this.data = data; this.cb = cb; this.longCb = longCb;
        }

        @Override public int getItemCount() { return data.size(); }

        @NonNull @Override public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new CardVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull CardVH h, int p) {
            final Item it = data.get(p);
            h.title.setText(it.title);
            h.sub.setText(it.subtitle);
            Api.thumb(h.cover, it.thumb, Ui.dp(h.cover.getContext(), 140), "artist".equals(it.kind) ? 70 : 6);
            h.itemView.setOnClickListener(x -> cb.pick(it, data));
            if (longCb != null) {
                h.itemView.setOnLongClickListener(x -> { longCb.longPick(it, data); return true; });
            }
        }
    }

    // ---------- Daftar seksi beranda ----------
    static class SecVH extends RecyclerView.ViewHolder {
        final TextView title; final RecyclerView rv;
        SecVH(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            rv = v.findViewById(R.id.rv);
        }
    }

    public static class Sections extends RecyclerView.Adapter<SecVH> {
        private final List<Api.Section> data; private final OnPick cb;
        private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        private OnLongPick longCb;

        public Sections(List<Api.Section> data, OnPick cb) { this.data = data; this.cb = cb; }

        public void setLongPick(OnLongPick l) { this.longCb = l; }

        @Override public int getItemCount() { return data.size(); }

        @NonNull @Override public SecVH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            SecVH h = new SecVH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_section, parent, false));
            h.rv.setLayoutManager(new LinearLayoutManager(parent.getContext(), LinearLayoutManager.HORIZONTAL, false));
            h.rv.setRecycledViewPool(pool);
            return h;
        }

        @Override public void onBindViewHolder(@NonNull SecVH h, int p) {
            Api.Section s = data.get(p);
            h.title.setText(s.title);
            h.rv.setAdapter(new Cards(s.items, cb, longCb));
        }
    }

    // ---------- Saran pencarian (teks saja) ----------
    public static class Suggests extends RecyclerView.Adapter<Suggests.VH> {
        public interface OnPick { void pick(String s); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title;
            VH(View v) { super(v); title = v.findViewById(R.id.title); }
        }

        private final java.util.List<String> data;
        private final OnPick cb;

        public Suggests(java.util.List<String> data, OnPick cb) {
            this.data = data;
            this.cb = cb;
        }

        @Override public int getItemCount() { return data == null ? 0 : data.size(); }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            final String s = data.get(p);
            h.title.setText(s);
            View sub = h.itemView.findViewById(R.id.sub);
            View dur = h.itemView.findViewById(R.id.dur);
            View cover = h.itemView.findViewById(R.id.cover);
            if (sub != null) sub.setVisibility(View.GONE);
            if (dur != null) dur.setVisibility(View.GONE);
            if (cover != null) cover.setVisibility(View.GONE);
            h.itemView.setOnClickListener(x -> cb.pick(s));
        }
    }
}
