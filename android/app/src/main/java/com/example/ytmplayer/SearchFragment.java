package com.example.ytmplayer;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

public class SearchFragment extends BaseFragment {
    private static final String[] KINDS = {"songs", "albums", "artists", "playlists"};
    private final TextView[] chips = new TextView[4];
    private EditText q;
    private int sel = 0;
    private Adapters.Rows rows;

    @Override protected int layout() { return R.layout.fragment_search; }

    @Override protected void init(View v) {
        q = v.findViewById(R.id.q);
        int[] ids = {R.id.chip0, R.id.chip1, R.id.chip2, R.id.chip3};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            chips[i] = v.findViewById(ids[i]);
            chips[i].setOnClickListener(x -> { sel = k; paint(); run(); });
        }
        paint();
        rows = new Adapters.Rows((it, ctx) -> main().open(it, ctx));
        list.setAdapter(rows);
        q.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { run(); return true; }
            return false;
        });
    }

    private void paint() {
        for (int i = 0; i < 4; i++) chips[i].setSelected(i == sel);
    }

    private void run() {
        String s = q.getText().toString().trim();
        if (s.isEmpty()) return;
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(q.getWindowToken(), 0);
        busy(true);
        Api.search(requireContext(), s, KINDS[sel], new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                if (!isAdded()) return;
                busy(false);
                rows.set(r.items);
                if (r.items == null || r.items.isEmpty()) message("Tidak ada hasil");
            }

            @Override public void err(String m) { if (isAdded()) fail(m, SearchFragment.this::run); }
        });
    }
}
