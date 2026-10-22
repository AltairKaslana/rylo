package com.example.ytmplayer;

import android.view.View;

public class LibraryFragment extends BaseFragment {
    @Override protected void init(View v) { load(); }

    private void load() {
        busy(true);
        Api.library(requireContext(), new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                if (!isAdded()) return;
                busy(false);
                Adapters.Rows rows = new Adapters.Rows((it, ctx) -> main().open(it, ctx));
                rows.set(r.items);
                list.setAdapter(rows);
            }

            @Override public void err(String m) { if (isAdded()) fail(m, LibraryFragment.this::load); }
        });
    }
}
