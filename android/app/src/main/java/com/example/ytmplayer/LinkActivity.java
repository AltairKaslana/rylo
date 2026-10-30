package com.example.ytmplayer;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Halaman "Hubungkan YouTube Music".
 *
 * Kenapa WebView, bukan dialog tempel-tekan: header cookie YouTube panjang dan
 * rawan salah salin. Dengan WebView, pengguna menempelnya di halaman resmi server
 * (halaman /setup) dan tidak perlu mengetik apa pun di chat atau di log.
 *
 * Kenapa URL memakai Api.setupUrl(): alamat itu memakai jalur cadangan TLS untuk
 * HP lama. Kalau halaman ini dibuka lewat alamat publik, HP KitKat gagal
 * handshake ECDSA dan halaman tidak pernah muncul - padahal API-nya jalan.
 *
 * Halaman ini meminta sesi (token) di query, jadi hanya bisa dibuka oleh orang
 * yang sudah masuk; server menolak penyimpanan tanpa sesi yang sah.
 */
public class LinkActivity extends AppCompatActivity {

    private WebView web;
    private ProgressBar bar;
    private TextView note;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p);
        box.setBackgroundColor(0xFF0D1117);

        note = new TextView(this);
        note.setText("Tempel header dari browser PC di halaman ini, lalu tekan Simpan.\n"
                + "Header disimpan hanya untuk akun Anda di server.");
        note.setTextSize(12);
        note.setTextColor(0xFF9AA0A6);
        note.setPadding(0, 0, 0, Ui.dp(this, 8));
        box.addView(note);

        bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        box.addView(bar);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0D1117);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);            // halaman /setup butuh JS untuk menyimpan
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setSaveFormData(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // Jangan biarkan halaman ini mengarah ke luar: cukup muat sendiri.
                if (url == null) return true;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    v.loadUrl(url);
                    return true;
                }
                return true;
            }
        });
        box.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        setContentView(box);

        String url = Api.setupUrl(this);
        if (!Prefs.loggedIn(this)) {
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("Perlu masuk dulu")
                    .setMessage("Hubungkan YouTube Music hanya bisa dilakukan setelah masuk "
                            + "dengan akun profil, supaya header tersimpan untuk akun yang benar.")
                    .setPositiveButton("Tutup", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
