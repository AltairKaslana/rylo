package com.example.ytmplayer;

import android.view.View;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/** Tab Pustaka: koleksi lokal (favorit, riwayat, playlist buatan sendiri). */
public class LibraryFragment extends BaseFragment {
    private Adapters.Rows rows;
    private final List<Item> entries = new ArrayList<>();

    @Override protected void init(View v) {
        rows = new Adapters.Rows((it, ctx) -> open(it));
        rows.setLongPick((it, ctx) -> {
            if ("playlist".equals(it.kind)) playlistMenu(it);
        });
        list.setAdapter(rows);
        load();
    }

    void load() {
        busy(true);
        Api.playlists(requireContext(), new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                if (!isAdded()) return;
                busy(false);
                entries.clear();
                entries.add(row("fav", "Favorit", "lagu yang ditandai"));
                entries.add(row("hist", "Riwayat", "yang terakhir diputar"));
                entries.add(row("newpl", "+ Buat playlist baru", ""));
                if (r.items != null) {
                    for (Item p : r.items) {
                        p.kind = "playlist";
                        entries.add(p);
                    }
                }
                rows.set(entries);
            }

            @Override public void err(String m) { if (isAdded()) fail(m, LibraryFragment.this::load); }
        });
    }

    private Item row(String kind, String title, String sub) {
        Item it = new Item();
        it.kind = kind;
        it.id = kind;
        it.title = title;
        it.subtitle = sub;
        it.thumb = "";
        return it;
    }

    private void open(Item it) {
        if ("newpl".equals(it.kind)) {
            newPlaylist();
        } else if ("fav".equals(it.kind)) {
            go(LocalFragment.make(LocalFragment.MODE_FAV, "", "Favorit"));
        } else if ("hist".equals(it.kind)) {
            go(LocalFragment.make(LocalFragment.MODE_HIST, "", "Riwayat"));
        } else if ("playlist".equals(it.kind)) {
            go(LocalFragment.make(LocalFragment.MODE_PL, it.id, it.title));
        }
    }

    private void go(androidx.fragment.app.Fragment f) {
        getParentFragmentManager().beginTransaction()
                .replace(R.id.content, f)
                .addToBackStack(null)
                .commit();
    }

    private void newPlaylist() {
        final EditText e = new EditText(requireContext());
        e.setHint("Nama playlist");
        new androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Playlist baru")
                .setView(e)
                .setPositiveButton("Buat", (d, w) -> {
                    String name = e.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Api.createPlaylist(requireContext(), name, new Api.Cb<Item>() {
                        @Override public void ok(Item pl) { load(); }
                        @Override public void err(String m) { main().toast(m); }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void playlistMenu(final Item it) {
        final String[] ops = {"Ganti nama", "Hapus playlist"};
        new androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle(it.title)
                .setItems(ops, (d, i) -> {
                    if (i == 0) rename(it);
                    else delete(it);
                })
                .show();
    }

    private void rename(final Item it) {
        final EditText e = new EditText(requireContext());
        e.setText(it.title);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Ganti nama")
                .setView(e)
                .setPositiveButton("Simpan", (d, w) -> {
                    String name = e.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Api.renamePlaylist(requireContext(), it.id, name, new Api.Cb<Api.Ok>() {
                        @Override public void ok(Api.Ok r) { load(); }
                        @Override public void err(String m) { main().toast(m); }
                    });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void delete(final Item it) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Hapus playlist?")
                .setMessage(it.title)
                .setPositiveButton("Hapus", (d, w) ->
                        Api.deletePlaylist(requireContext(), it.id, new Api.Cb<Api.Ok>() {
                            @Override public void ok(Api.Ok r) { load(); }
                            @Override public void err(String m) { main().toast(m); }
                        }))
                .setNegativeButton("Batal", null)
                .show();
    }
}
