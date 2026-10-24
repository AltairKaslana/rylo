package com.example.ytmplayer;

import android.view.View;

public class HomeFragment extends BaseFragment {
    @Override protected void init(View v) { load(); }

    private void load() {
        busy(true);
        Api.home(requireContext(), new Api.Cb<Api.Home>() {
            @Override public void ok(Api.Home r) {
                if (!isAdded()) return;
                busy(false);
                if (r.sections == null || r.sections.isEmpty()) { message("Beranda kosong"); return; }
                show(r.sections);
                addRecs(r.sections);
            }

            @Override public void err(String m) { if (isAdded()) fail(m, HomeFragment.this::load); }
        });
    }

    private void show(java.util.List<Api.Section> sections) {
        // Beranda (termasuk "Rekomendasi untukmu") adalah daftar penemuan, bukan koleksi
        // yang disusun pengguna: putar lagu yang diketuk, lanjutannya dari radio lagu itu.
        Adapters.Sections a = new Adapters.Sections(sections, (it, ctx) -> main().open(it, ctx, false));
        a.setLongPick((it, ctx) -> main().trackMenu(it, "", "", null));
        list.setAdapter(a);
    }

    /** Rekomendasi dari kebiasaan sendiri (favorit + riwayat), bukan dari akun YouTube. */
    private void addRecs(final java.util.List<Api.Section> sections) {
        // tampilkan dulu beranda apa adanya, rekomendasi menyusul tanpa menghalangi
        Api.recs(requireContext(), new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                if (!isAdded() || r.items == null || r.items.isEmpty()) return;
                Api.Section s = new Api.Section();
                s.title = "Rekomendasi untukmu";
                s.items = r.items;
                sections.add(0, s);
                show(sections);
            }

            @Override public void err(String m) { /* beranda tetap tampil */ }
        });
    }
}
