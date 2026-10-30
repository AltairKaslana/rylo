package com.example.ytmplayer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Layar masuk: username + sandi per profil.
 *
 * Kenapa ada: dulu data favorit/playlist/riwayat hanya dilindungi PIN server, dan
 * profil cukup dikirim sebagai header X-Profile bebas - siapa pun yang tahu PIN bisa
 * membaca data profil orang lain. Sekarang server menolak endpoint data pengguna
 * tanpa sesi login, jadi layar ini wajib dilewati sebelum aplikasi dipakai.
 *
 * Kunci master (PIN server) tetap dipakai untuk adopsi profil lama, supaya riwayat
 * pemasangan sebelumnya tidak hilang.
 *
 * Sejak server dibagikan ke publik, dua hal berubah di layar ini:
 * 1. Alamat server TIDAK lagi ditampilkan. Aplikasi sudah membawa alamat bawaan,
 *    dan menampilkannya di layar yang dilihat siapa saja berarti membocorkan
 *    informasi yang tidak perlu. Jalur admin (tekan lama judul 3 detik) tetap ada
 *    untuk mengganti alamat saat perlu.
 * 2. Daftar akun memakai KODE UNDANGAN dari Tuan, bukan PIN master - alamat server
 *    bisa dilihat orang lain, jadi PIN master tidak boleh jadi syarat yang bisa
 *    ditebak orang.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText fServer, fPin, fCode, fUser, fPass, fAlias, fOld;
    private TextView title, hint;
    private Button btnLogin, btnRegister, btnToggle;
    private View extraBox, serverBox;
    private boolean registerMode = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);

        title = findViewById(R.id.loginTitle);
        hint = findViewById(R.id.loginHint);
        fServer = findViewById(R.id.fServer);
        fPin = findViewById(R.id.fPin);
        fUser = findViewById(R.id.fUser);
        fPass = findViewById(R.id.fPass);
        fAlias = findViewById(R.id.fAlias);
        fOld = findViewById(R.id.fOld);
        extraBox = findViewById(R.id.extraBox);
        serverBox = findViewById(R.id.serverBox);
        btnLogin = findViewById(R.id.btnLogin);
        btnRegister = findViewById(R.id.btnRegister);
        btnToggle = findViewById(R.id.btnToggle);

        // Alamat server diisi diam-diam dari bawaan; kolomnya hanya muncul lewat
        // jalan admin (tekan lama judul), jadi pengguna baru tidak melihat alamat.
        String srv = Prefs.server(this);
        if (srv.isEmpty()) srv = Prefs.DEFAULT_SERVER;
        fServer.setText(srv);
        fPin.setText(Prefs.token(this));
        serverBox.setVisibility(View.GONE);

        // Jalan admin tersembunyi: tekan lama judul "Masuk".
        title.setOnLongClickListener(v -> {
            serverBox.setVisibility(View.VISIBLE);
            toast("Mode admin: alamat server & kunci master bisa diubah");
            return true;
        });

        fPass.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        btnLogin.setOnClickListener(v -> doLogin());
        btnRegister.setOnClickListener(v -> doRegister());
        btnToggle.setOnClickListener(v -> setMode(!registerMode));
        setMode(false);
    }

    private void setMode(boolean reg) {
        registerMode = reg;
        extraBox.setVisibility(reg ? View.VISIBLE : View.GONE);
        title.setText(reg ? "Buat profil baru" : "Masuk");
        hint.setText(reg
                ? "Daftar memakai kode undangan dari Tuan. Kolom \"profil lama\" diisi kalau "
                  + "pernah memasang aplikasi ini sebelumnya dan mau melanjutkan riwayatnya."
                : "Masuk dengan username dan sandi profil.");
        btnToggle.setText(reg ? "Sudah punya akun? Masuk" : "Belum punya akun? Daftar");
    }

    private void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
    }

    /** Simpan alamat server + kunci master kalau diisi di layar ini. */
    private boolean saveServerIfNeeded() {
        if (Prefs.ready(this)) return true;
        String srv = fServer.getText().toString().trim();
        String pin = fPin.getText().toString().trim();
        if (srv.isEmpty() || pin.isEmpty()) {
            toast("Alamat server dan kunci master belum lengkap");
            return false;
        }
        Prefs.save(this, srv, pin);
        return true;
    }

    private void doLogin() {
        if (!saveServerIfNeeded()) return;
        final String u = fUser.getText().toString().trim();
        final String p = fPass.getText().toString();
        if (u.isEmpty() || p.isEmpty()) { toast("Username dan sandi belum lengkap"); return; }
        Api.login(this, u, p, new Api.Cb<Api.Session>() {
            @Override public void ok(Api.Session s) { finishLogin(s); }
            @Override public void err(String m) { toast(m); }
        });
    }

    private void doRegister() {
        if (!saveServerIfNeeded()) return;
        final String u = fUser.getText().toString().trim();
        final String p = fPass.getText().toString();
        final String a = fAlias.getText().toString().trim();
        final String code = fCode.getText().toString().trim();
        final String old = fOld.getText().toString().trim();
        if (u.isEmpty() || p.isEmpty()) { toast("Username dan sandi belum lengkap"); return; }
        if (p.length() < 6) { toast("Sandi minimal 6 karakter"); return; }
        if (code.isEmpty()) { toast("Kode undangan dari Tuan belum diisi"); return; }
        Api.register(this, u, p, a.isEmpty() ? u : a, code, new Api.Cb<Api.Session>() {
            @Override public void ok(Api.Session s) {
                if (old.isEmpty()) { finishLogin(s); return; }
                // Setelah akun jadi, pindahkan data profil lama ke akun ini.
                Api.adopt(LoginActivity.this, old, new Api.Cb<Api.Adopt>() {
                    @Override public void ok(Api.Adopt r) {
                        toast("Data lama dipindahkan (" + r.moved + " lagu)");
                        finishLogin(s);
                    }
                    @Override public void err(String m) {
                        toast("Akun jadi, tapi data lama gagal dipindahkan: " + m);
                        finishLogin(s);
                    }
                });
            }
            @Override public void err(String m) { toast(m); }
        });
    }

    private void finishLogin(Api.Session s) {
        if (s == null || s.token == null || s.token.isEmpty()) {
            toast("Server tidak mengirim token sesi");
            return;
        }
        Prefs.saveSession(this, s.token, s.username, s.alias);
        Api.clearCache();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    /** Dipanggil MainActivity kalau sesi tidak ada lagi. */
    public static void ensureLoggedIn(Context c) {
        if (!Prefs.loggedIn(c)) {
            Intent i = new Intent(c, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            c.startActivity(i);
        }
    }
}
