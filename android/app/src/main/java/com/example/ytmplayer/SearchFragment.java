package com.example.ytmplayer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class SearchFragment extends BaseFragment {
    private static final String[] KINDS = {"songs", "albums", "artists", "playlists"};
    private final TextView[] chips = new TextView[4];
    private EditText q;
    private int sel = 0;
    private Adapters.Rows rows;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private String lastSuggest = "";
    private View recentPanel, recentEmpty;
    private TextView recentClear;
    private RecyclerView recentList;
    private Adapters_Recent.RecentAdapter recentAdapter;
    private final java.util.List<Api.Recent> recentData = new java.util.ArrayList<>();

    @Override protected int layout() { return R.layout.fragment_search; }

    @Override protected void init(View v) {
        q = v.findViewById(R.id.q);
        int[] ids = {R.id.chip0, R.id.chip1, R.id.chip2, R.id.chip3};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            chips[i] = v.findViewById(ids[i]);
            chips[i].setOnClickListener(x -> { sel = k; paint(); run(); });
        }
        recentPanel = v.findViewById(R.id.recentPanel);
        recentEmpty = v.findViewById(R.id.recentEmpty);
        recentClear = v.findViewById(R.id.recentClear);
        recentList = v.findViewById(R.id.recentList);
        recentList.setLayoutManager(new LinearLayoutManager(requireContext()));
        recentAdapter = new Adapters_Recent.RecentAdapter(recentData,
                r -> {                       // ketuk -> cari lagi
                    q.setText(r.query);
                    q.setSelection(r.query.length());
                    run();
                },
                r -> {                       // ikon silang -> hapus satu
                    Api.forgetSearch(requireContext(), r.query);
                    recentData.remove(r);
                    recentAdapter.notifyDataSetChanged();
                    paintRecent();
                });
        recentList.setAdapter(recentAdapter);

        recentClear.setOnClickListener(x -> {
            Api.forgetSearch(requireContext(), "");
            recentData.clear();
            recentAdapter.notifyDataSetChanged();
            paintRecent();
        });

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
                paintRecent(text.isEmpty());
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

    /** true = kotak pencarian kosong, tampilkan panel pencarian terakhir. */
    private void paintRecent(boolean kosong) {
        if (recentPanel == null) return;
        recentPanel.setVisibility(kosong ? View.VISIBLE : View.GONE);
        if (!kosong) return;
        boolean ada = recentData != null && !recentData.isEmpty();
        recentList.setVisibility(ada ? View.VISIBLE : View.GONE);
        recentEmpty.setVisibility(ada ? View.GONE : View.VISIBLE);
        recentClear.setVisibility(ada ? View.VISIBLE : View.GONE);
    }

    /** Muat pencarian terakhir dari server (ikut profil, jadi sama di semua HP). */
    public void loadRecent() {
        Api.recentSearches(requireContext(), new Api.Cb<Api.Recents>() {
            @Override public void ok(Api.Recents r) {
                if (!isAdded()) return;
                recentData.clear();
                if (r != null && r.items != null) recentData.addAll(r.items);
                if (recentAdapter != null) recentAdapter.notifyDataSetChanged();
                paintRecent(q.getText().toString().trim().isEmpty());
            }
            @Override public void err(String m) { }
        });
    }

    private void run() {
        final String s = q.getText().toString().trim();
        if (s.isEmpty()) { paintRecent(true); return; }
        cancel();
        Api.rememberSearch(requireContext(), s, KINDS[sel]);   // jadikan rekomendasi nanti
        paintRecent(false);
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

    @Override public void onResume() {
        super.onResume();
        loadRecent();
    }

    @Override public void onDestroyView() {
        cancel();
        super.onDestroyView();
    }
}
