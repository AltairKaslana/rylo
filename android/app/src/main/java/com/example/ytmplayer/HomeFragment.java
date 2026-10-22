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
                list.setAdapter(new Adapters.Sections(r.sections, (it, ctx) -> main().open(it, ctx)));
            }

            @Override public void err(String m) { if (isAdded()) fail(m, HomeFragment.this::load); }
        });
    }
}
