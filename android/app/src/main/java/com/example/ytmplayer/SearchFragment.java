package com.example.ytmplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private String lastSuggest = "";

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
        rows.setLongPick((it, ctx) -> main().trackMenu(it, "", "", null));
        list.setAdapter(rows);
        q.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { cancel(); run(); return true; }
            return false;
        });
        // Saran pencarian saat mengetik: jeda 350 ms, minimal 2 huruf
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                final String text = e.toString().trim();
                if (pending != null) h.removeCallbacks(pending);
                if (text.length() < 2) {
                    lastSuggest = "";
                    return;
                }
                pending = new Runnable() {
                    @Override public void run() { suggest(text); }
                };
                h.postDelayed(pending, 350);
            }
        });
    }

    private void cancel() {
        if (pending != null) h.removeCallbacks(pending);
    }

    private void suggest(final String text) {
        if (!isAdded() || text.equals(lastSuggest)) return;
        lastSuggest = text;
        Api.suggest(requireContext(), text, new Api.Cb<Api.Suggest>() {
            @Override public void ok(Api.Suggest r) {
                if (!isAdded() || !text.equals(q.getText().toString().trim())) return;
                if (r.items == null || r.items.isEmpty()) return;
                list.setAdapter(new Adapters.Suggests(r.items, new Adapters.Suggests.OnPick() {
                    @Override public void pick(String s) {
                        q.setText(s);
                        q.setSelection(s.length());
                        run();
                    }
                }));
            }

            @Override public void err(String m) { /* senyap saat mengetik */ }
        });
    }

    private void paint() {
        for (int i = 0; i < 4; i++) chips[i].setSelected(i == sel);
    }

    private void run() {
        final String s = q.getText().toString().trim();
        if (s.isEmpty()) return;
        cancel();
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(q.getWindowToken(), 0);
        busy(true);
        Api.search(requireContext(), s, KINDS[sel], new Api.Cb<Api.Items>() {
            @Override public void ok(Api.Items r) {
                if (!isAdded()) return;
                busy(false);
                list.setAdapter(rows);
                rows.set(r.items);
                if (r.items == null || r.items.isEmpty()) message("Tidak ada hasil");
            }

            @Override public void err(String m) { if (isAdded()) fail(m, SearchFragment.this::run); }
        });
    }

    @Override public void onDestroyView() {
        cancel();
        super.onDestroyView();
    }
}
