package com.example.ytmplayer;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Panel equalizer.
 *
 * Bentuk UI: DERETAN PRESET dulu, slider hanya muncul saat memilih "Custom".
 * Alasannya layar: HP target Rylo 480x800 dan dipakai satu tangan, sementara lima
 * slider memakan hampir seluruh tinggi dialog. Preset sudah menutup hampir semua
 * kebutuhan orang.
 *
 * Seluruh tampilan dibangun dari kode (bukan XML) mengikuti cara dialog setelan
 * yang sudah ada, supaya tidak ada berkas layout baru yang harus dirawat.
 */
public final class EqPanel {

    private static final int BG_IDLE = 0xFF2A2D33;
    private static final int BG_ACTIVE = 0xFFE4BE5B;
    private static final int BG_ON = 0xFF2E7D52;
    private static final int FG_IDLE = 0xFFFFFFFF;
    private static final int FG_ACTIVE = 0xFF2D1B04;

    private EqPanel() {}

    public static void show(final Activity act) {
        final Context c = act;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 18);
        root.setPadding(pad, Ui.dp(c, 8), pad, Ui.dp(c, 4));

        final TextView status = new TextView(c);
        status.setTextSize(12);
        status.setTextColor(0xFF9AA0A6);
        root.addView(status);

        final Button power = new Button(c);
        power.setTextColor(FG_IDLE);
        root.addView(power);

        final TextView labelPreset = new TextView(c);
        labelPreset.setText("Preset");
        labelPreset.setTextSize(12);
        labelPreset.setTextColor(0xFFFFB27A);
        labelPreset.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6));
        root.addView(labelPreset);

        final LinearLayout presetRow = new LinearLayout(c);
        presetRow.setOrientation(LinearLayout.VERTICAL);
        root.addView(presetRow);

        // Slot refresh: semua listener memanggilnya, isinya bergantung pada widget
        // yang baru dibuat setelah baris ini.
        final Runnable[] refresh = new Runnable[1];

        final Button[] presetBtns = new Button[Eq.PRESETS.length];
        for (int i = 0; i < Eq.PRESETS.length; i++) {
            final int idx = i;
            Button b = new Button(c);
            b.setText(Eq.PRESETS[i]);
            b.setTextSize(13);
            b.setTextColor(FG_IDLE);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Eq.setPresetIndex(c, idx);
                    if (!Eq.enabled(c)) Eq.setEnabled(c, true);
                    Eq.apply(c);
                    refresh[0].run();
                }
            });
            presetBtns[i] = b;
        }

        // Dua kolom supaya enam preset muat tanpa menggulir di layar 480x800.
        for (int i = 0; i < presetBtns.length; i += 2) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.addView(row);
            for (int j = i; j < i + 2 && j < presetBtns.length; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = Ui.dp(c, 4);
                lp.bottomMargin = Ui.dp(c, 4);
                presetBtns[j].setLayoutParams(lp);
                row.addView(presetBtns[j]);
            }
        }

        final LinearLayout customBox = new LinearLayout(c);
        customBox.setOrientation(LinearLayout.VERTICAL);
        customBox.setVisibility(View.GONE);
        root.addView(customBox);

        int[] hz = Eq.bandFrequencies();
        final SeekBar[] bars = new SeekBar[hz.length];
        final TextView[] vals = new TextView[hz.length];
        for (int i = 0; i < hz.length; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(c);
            name.setText(hz[i] >= 1000 ? (hz[i] / 1000) + "k" : String.valueOf(hz[i]));
            name.setTextSize(11);
            name.setTextColor(0xFF9AA0A6);
            name.setWidth(Ui.dp(c, 42));
            row.addView(name);

            final SeekBar sb = new SeekBar(c);
            sb.setMax(Eq.CUSTOM_MAX - Eq.CUSTOM_MIN);
            sb.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(sb);

            final TextView v = new TextView(c);
            v.setTextSize(11);
            v.setTextColor(0xFF9AA0A6);
            v.setGravity(Gravity.END);
            v.setWidth(Ui.dp(c, 46));
            row.addView(v);

            final int bi = i;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    short gain = (short) (progress + Eq.CUSTOM_MIN);
                    v.setText(fmt(gain));
                    if (!fromUser) return;
                    short[] g = Eq.customGains(c);
                    g[bi] = gain;
                    Eq.setCustomGains(c, g);
                    Eq.setPresetIndex(c, Eq.PRESETS.length - 1);
                    if (!Eq.enabled(c)) Eq.setEnabled(c, true);
                    Eq.apply(c);
                    refresh[0].run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            });
            bars[i] = sb;
            vals[i] = v;
            customBox.addView(row);
        }

        final TextView labelBass = new TextView(c);
        labelBass.setText("Bass tambahan");
        labelBass.setTextSize(12);
        labelBass.setTextColor(0xFFFFB27A);
        labelBass.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 2));
        root.addView(labelBass);

        final SeekBar bassBar = new SeekBar(c);
        bassBar.setMax(100);
        root.addView(bassBar);

        final TextView bassVal = new TextView(c);
        bassVal.setTextSize(11);
        bassVal.setTextColor(0xFF9AA0A6);
        root.addView(bassVal);

        bassBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                bassVal.setText(progress + "%");
                if (!fromUser) return;
                Eq.setBassStrength(c, progress);
                if (!Eq.enabled(c)) Eq.setEnabled(c, true);
                Eq.apply(c);
                refresh[0].run();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        final Button reset = new Button(c);
        reset.setText("Kembalikan ke Flat");
        reset.setTextColor(FG_IDLE);
        reset.setBackgroundColor(BG_IDLE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 14);
        reset.setLayoutParams(rlp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Eq.setPresetIndex(c, 0);
                Eq.setBassStrength(c, 0);
                Eq.setCustomGains(c, new short[]{0, 0, 0, 0, 0});
                Eq.apply(c);
                refresh[0].run();
                Toast.makeText(c, "Equalizer kembali ke Flat", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(reset);

        power.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Eq.setEnabled(c, !Eq.enabled(c));
                Eq.apply(c);
                refresh[0].run();
            }
        });

        refresh[0] = new Runnable() {
            @Override public void run() {
                boolean on = Eq.enabled(c);
                int idx = Eq.presetIndex(c);

                power.setText(on ? "Equalizer: AKTIF" : "Equalizer: MATI");
                power.setBackgroundColor(on ? BG_ON : BG_IDLE);

                for (int i = 0; i < presetBtns.length; i++) {
                    presetBtns[i].setBackgroundColor(i == idx ? BG_ACTIVE : BG_IDLE);
                    presetBtns[i].setTextColor(i == idx ? FG_ACTIVE : FG_IDLE);
                }
                customBox.setVisibility(idx == Eq.PRESETS.length - 1 ? View.VISIBLE : View.GONE);

                short[] g = Eq.activeGains(c);
                for (int i = 0; i < bars.length; i++) {
                    int progress = Math.max(0, Math.min(bars[i].getMax(), g[i] - Eq.CUSTOM_MIN));
                    bars[i].setProgress(progress);
                    vals[i].setText(fmt(g[i]));
                }

                boolean hasBass = Eq.hasBassBoost();
                labelBass.setVisibility(hasBass ? View.VISIBLE : View.GONE);
                bassBar.setVisibility(hasBass ? View.VISIBLE : View.GONE);
                bassVal.setVisibility(hasBass ? View.VISIBLE : View.GONE);
                bassBar.setProgress(Eq.bassStrength(c));
                bassVal.setText(Eq.bassStrength(c) + "%");

                status.setText(on
                        ? "Berlaku untuk pemutaran di HP ini saja."
                        : "Equalizer mati. Suara diputar apa adanya dari server.");
            }
        };
        refresh[0].run();

        ScrollView sc = new ScrollView(c);
        sc.addView(root);
        new android.app.AlertDialog.Builder(c, R.style.DialogTheme)
                .setTitle("Equalizer")
                .setView(sc)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private static String fmt(short millibel) {
        float db = millibel / 100f;
        return (db > 0 ? "+" : "") + String.format(java.util.Locale.US, "%.1f", db);
    }
}
