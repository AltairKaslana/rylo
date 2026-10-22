package com.example.ytmplayer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public abstract class BaseFragment extends Fragment {
    protected RecyclerView list;
    protected View progress;
    protected TextView empty;

    protected int layout() { return R.layout.fragment_list; }

    protected abstract void init(View root);

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        return inf.inflate(layout(), c, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        list = v.findViewById(R.id.list);
        progress = v.findViewById(R.id.progress);
        empty = v.findViewById(R.id.empty);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        init(v);
    }

    protected MainActivity main() { return (MainActivity) requireActivity(); }

    protected void busy(boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) empty.setVisibility(View.GONE);
    }

    protected void message(String m) {
        busy(false);
        empty.setText(m);
        empty.setVisibility(View.VISIBLE);
    }

    protected void fail(String m, final Runnable retry) {
        message(m + "\n\n(ketuk untuk coba lagi)");
        empty.setOnClickListener(v -> retry.run());
    }
}
