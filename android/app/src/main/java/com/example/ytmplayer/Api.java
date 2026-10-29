package com.example.ytmplayer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Klien HTTP ke server relay (HTTP biasa di LAN, jadi tidak ada masalah TLS di KitKat). */
public final class Api {
    private Api() {}

    public interface Cb<T> {
        void ok(T r);
        void err(String m);
    }


    static Context APP;

    /** Dipanggil sekali dari App.onCreate supaya fallback bisa mencatat log. */
    public static void attach(Context c) { APP = c.getApplicationContext(); }

    /**
     * Header untuk endpoint data pengguna (/lib/*): token sesi, BUKAN X-Profile.
     *
     * Server sengaja tidak lagi mempercayai header X-Profile - dulu siapa pun yang
     * tahu PIN bisa membaca data profil orang lain cukup dengan mengganti header itu.
     * Sekarang profil ditentukan dari sesi login, jadi endpoint /lib/* menolak request
     * tanpa sesi yang sah.
     */
    static Request.Builder authed(Request.Builder b, Context c) {
        return b.header("X-Session", Prefs.session(c));
    }

    /** Host publik -> alamat cadangan (HTTP polos) untuk HP yang TLS-nya tidak sanggup. */
    /**
     * Jalur cadangan TLS: stunnel di box memakai sertifikat RSA sendiri (dipin di Net.java).
     * Dipakai bila HTTPS Cloudflare gagal karena HP lama tidak mendukung cipher ECDSA.
     */
    private static final String BORE_HOST = "bore.pub";
    /**
     * Port tunnel TLS (stunnel). HANYA ini yang masih hidup.
     *
     * 24191/24192/24194/24195 dulu ada di daftar ini sebagai cadangan waktu port
     * ikut drift (pitfall 28). Sekarang keempatnya sudah tidak ada sama sekali,
     * dan membiarkannya di daftar berarti SETIAP kegagalan jaringan menghabiskan
     * empat percobaan ke port mati lebih dulu. Terbukti di netlog 29 Okt:
     * 10:57:11 -> 10:57:33 = 22 detik jatuh-bangun sebelum "recs ok", padahal
     * port yang hidup cuma satu dan ia yang pertama.
     */
    private static final int[] BORE_PORTS = {24193};
    /**
     * Port HTTP POLOS (bore -> relay, tanpa stunnel). Cadangan TERAKHIR, hanya
     * dipakai kalau TLS mati total. Skemanya "http" BUKAN "https" - mencoba TLS
     * ke port ini selalu berakhir "Unable to parse TLS packet header", jadi
     * skema harus ikut port, jangan diasumsikan https.
     */
    private static final int PLAIN_PORT = 24190;

    /** Alamat tunnel efektif. Skema mengikuti port supaya 24190 tidak dicoba TLS. */
    public static String boreBase(Context c) {
        int p = activePort(c);
        return (p == PLAIN_PORT ? "http" : "https") + "://" + BORE_HOST + ":" + p;
    }

    private static boolean hasFallback(String host) {
        return "musik.altairk.my.id".equals(host) || BORE_HOST.equals(host);
    }

    /**
     * Kalau endpoint data pengguna menjawab 401, sesi sudah tidak sah (kedaluwarsa
     * atau dicabut). Buang sesi dan kembalikan pengguna ke layar masuk - tanpa ini
     * aplikasi terus memunculkan "Server merespons 401" di setiap tab.
     */
    private static void handleAuthFail(Context app, int code, String path) {
        if (code != 401 || !path.startsWith("/lib/")) return;
        if (!Prefs.loggedIn(app)) return;
        Prefs.clearSession(app);
        Intent i = new Intent(app, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        app.startActivity(i);
    }

    /** Escape minimal untuk menyusun JSON tangan (tanpa dependency tambahan). */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\');
            else if (ch == '\n') { b.append('\\'); b.append('n'); continue; }
            b.append(ch);
        }
        return b.toString();
    }

    /** Hasil login/daftar: token sesi + identitas profil. */
    public static class Session {
        public String token, username, profile, alias;
        public long expires_at;
    }

    /** Hasil adopsi profil lama. */
    public static class Adopt {
        public int moved;
        public String profile;
    }

    public static String hostOf(String url) {
        try { return java.net.URI.create(url).getHost(); } catch (Exception e) { return ""; }
    }

    /** Port yang harus dipakai: kalau sudah pernah berhasil, pakai itu. */
    public static int activePort(Context c) {
        int saved = Prefs.borePort(c);
        if (saved > 0) return saved;
        return BORE_PORTS[0];
    }

    /**
     * Cari port tunnel yang benar-benar bisa dihubungi.
     *
     * "Bisa dihubungi" berarti port MENJAWAB, bukan berarti auth lolos: port mati
     * gagal di tingkat koneksi (ConnectException), sedangkan port hidup yang
     * menolak token menjawab dengan kode HTTP. Karena itu syaratnya ada kode
     * respons (> 0), bukan 200 - kalau dituntut 200, aplikasi yang belum login
     * akan menganggap semua port mati dan tidak pernah mempelajari yang hidup.
     *
     * Fungsi ini TIDAK menyentuh Prefs.altTls: mengetahui port bukan berarti
     * memaksa jalur cadangan. Kalau domain Cloudflare sehat, ia harus tetap dipakai.
     */
    public static int probePort(Context c) {
        for (int p : BORE_PORTS) {
            if (jawab("https", p, c)) return p;
        }
        if (jawab("http", PLAIN_PORT, c)) return PLAIN_PORT;
        return 0;
    }

    /** true kalau port menjawab (kode HTTP berapa pun). */
    private static boolean jawab(String skema, int port, Context c) {
        try {
            Request r = new Request.Builder()
                    .url(skema + "://" + BORE_HOST + ":" + port + "/health?t=" + enc(Prefs.token(c)))
                    .header("X-Profile", Prefs.profile(c)).build();
            Response resp = HTTP.newCall(r).execute();
            int code = resp.code();
            resp.close();
            if (code > 0) {
                Prefs.setBorePort(c, port);
                return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * Pindah ke port berikutnya. Hanya dua kemungkinan, dan itu memang yang ada:
     * port TLS utama, lalu HTTP polos sebagai jalan terakhir.
     *
     * Fungsi ini SENGAJA menyimpan port yang akan dicoba walau belum tentu hidup -
     * yang memperbaikinya adalah onResponse, yang kini akhir benar-benar jalan
     * (lihat call()). Sebelumnya jalur coba-ulang memakai callback asli sehingga
     * keberhasilan tidak pernah tercatat, sementara kegagalan selalu tercatat.
     * Akibatnya yang diingat justru port yang mati, dan itulah yang membuat
     * aplikasi menyisir port mati berulang-ulang.
     */
    public static int nextPort(Context c) {
        int cur = activePort(c);
        int next = (cur != PLAIN_PORT) ? PLAIN_PORT : BORE_PORTS[0];
        Prefs.setBorePort(c, next);
        return next;
    }

    private static boolean tlsTrouble(IOException e) {
        if (e instanceof javax.net.ssl.SSLException) return true;
        if (e instanceof java.net.UnknownHostException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.SocketTimeoutException) return true;
        return false;
    }

    /**
     * Jalankan permintaan; bila jaringan/TLS gagal, ulangi ke jalur cadangan
     * (tunnel dengan sertifikat RSA kita).
     *
     * DUA hal diperbaiki di sini, dan dua-duanya sama-sama membuat aplikasi
     * menyisir port mati berulang-ulang:
     *
     * 1. **Coba-ulang kembali lewat call(), bukan enqueue(cb).** Sebelumnya
     *    permintaan ulangan memakai callback asli, sehingga onResponse di bawah
     *    tidak pernah jalan untuknya - keberhasilan tidak tercatat sama sekali,
     *    sementara nextPort() SELALU mencatat kegagalan. Yang tersimpan jadi
     *    port yang mati, dan itulah sebabnya port hidup tidak pernah diingat.
     * 2. **Ada batas kedalaman.** Karena coba-ulang kini terbungkus lagi, ia bisa
     *    mencoba port berikutnya dan seterusnya; tanpa batas, jaringan yang mati
     *    total membuat permintaan berputar selamanya.
     *
     * Skema ikut port (boreBase), jadi port HTTP polos tidak lagi dicoba TLS.
     */
    private static void call(final Request req, final Callback cb) {
        call(req, cb, 0);
    }

    private static void call(final Request req, final Callback cb, final int depth) {
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (APP != null) Log2.trace(APP, "fail proto=" + req.url().scheme()
                        + " tls=" + Net.provider() + " " + e.getClass().getSimpleName());

                String host = req.url().host();
                boolean dariDomain = hostOf(Prefs.server(APP)).equals(host);
                boolean bolehUlang = (dariDomain && hasFallback(host)) || BORE_HOST.equals(host);

                // Habis atau bukan soal jaringan: berhenti, jangan ulang lagi.
                if (depth >= BORE_PORTS.length + 1 || !bolehUlang || !tlsTrouble(e)) {
                    cb.onFailure(call, e);
                    return;
                }

                // Dari domain  -> pakai port terbaik yang sudah diketahui.
                // Dari bore    -> portnya mati, pindah (hasilnya tersimpan,
                //                 lalu dibaca boreBase()).
                if (!dariDomain) nextPort(APP);
                if (dariDomain) Prefs.setAltTls(APP, true);

                try {
                    String url = boreBase(APP) + req.url().encodedPath()
                            + (req.url().encodedQuery() == null ? "" : "?" + req.url().encodedQuery());
                    if (APP != null) Log2.trace(APP,
                            (dariDomain ? "tls fallback -> " : "port mati -> coba ") + url);
                    call(req.newBuilder().url(url).build(), cb, depth + 1);
                } catch (Throwable ignored) {
                    cb.onFailure(call, e);
                }
            }

            @Override public void onResponse(Call call, Response resp) throws IOException {
                String host = req.url().host();
                if (BORE_HOST.equals(host) && APP != null) {
                    Prefs.setBorePort(APP, req.url().port());
                    Prefs.setAltTls(APP, true);
                }
                cb.onResponse(call, resp);
            }
        });
    }

    /** Satu entri pencarian terakhir + daftar lagu yang pernah dipilih darinya. */
    public static class Recent {
        public String query, kind;
        public long at;
        public List<Item> items;

        /** True kalau ada lagu tersimpan -> bisa langsung diputar. */
        public boolean hasTrack() {
            return items != null && !items.isEmpty();
        }

        /** Lagu terbaru yang dipilih dari pencarian ini. */
        public Item lastTrack() {
            return hasTrack() ? items.get(0) : null;
        }
    }

    public static class Recents {
        public List<Recent> items;
    }

    /** Pencarian terakhir (tersimpan di server, ikut profil). */
    public static void recentSearches(Context c, Cb<Recents> cb) {
        get(c, "/lib/recent-searches?limit=12", 0, Recents.class, cb);
    }

    /** Simpan pencarian + lagu yang dipilih pengguna (WAJIB POST). */
    public static void rememberSearch(Context c, String q, String kind, Item it) {
        if (q == null || q.trim().isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("q", q);
        o.addProperty("kind", kind == null ? "songs" : kind);
        if (it != null) {
            o.addProperty("video_id", it.id);
            o.addProperty("title", it.title);
            o.addProperty("subtitle", it.subtitle);
            o.addProperty("thumb", it.thumb);
            o.addProperty("dur", it.dur);
        }
        send(c, "POST", "/lib/recent-searches", o.toString(),
                new Cb<Ok>() {
                    @Override public void ok(Ok r) { }
                    @Override public void err(String m) { }
                });
    }

    /** Hapus semua pencarian terakhir. */
    public static void forgetSearch(Context c, String q) {
        String path = "/lib/recent-searches" + (q == null || q.isEmpty() ? "" : "?q=" + enc(q));
        send(c, "DELETE", path, "{}", new Cb<Ok>() {
            @Override public void ok(Ok r) { }
            @Override public void err(String m) { }
        });
    }

    /** Hapus satu lagu dari riwayat sebuah pencarian. */
    public static void forgetSearchItem(Context c, String q, String vid) {
        String path = "/lib/recent-searches?q=" + enc(q) + "&vid=" + enc(vid);
        send(c, "DELETE", path, "{}", new Cb<Ok>() {
            @Override public void ok(Ok r) { }
            @Override public void err(String m) { }
        });
    }

    public static class Items { public List<Item> items; }
    public static class Section { public String title; public List<Item> items; }
    public static class Home { public List<Section> sections; }
    public static class Detail { public String kind, id, title, subtitle, thumb; public List<Item> tracks; }
    public static class Lyrics { public String lyrics; }
    public static class Suggest { public java.util.List<String> items; }
    public static class Ok { public boolean ok; public boolean fav; }

    private static final OkHttpClient HTTP = buildClient();

    /** Klien yang sama dipakai Glide & ExoPlayer supaya koneksi TLS dipakai ulang. */
    public static OkHttpClient client() { return HTTP; }

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS);
        if (Net.socketFactory() != null && Net.trustManager() != null) {
            b.sslSocketFactory(Net.socketFactory(), Net.trustManager());
            b.hostnameVerifier(Net.pinnedVerifier());
        }
        return b.build();
    }
    private static final Gson GSON = new Gson();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    private static String enc(String s) { return Uri.encode(s == null ? "" : s); }

    public static void clearCache() {
        synchronized (CACHE) { CACHE.clear(); }
    }

    /** Base URL efektif: jalur TLS cadangan dipakai bila sudah pernah diperlukan. */
    public static String base(Context c) {
        String srv = Prefs.server(c);
        if (!Prefs.altTls(c)) return srv;
        if (hasFallback(hostOf(srv))) return boreBase(c);
        return srv;
    }

    public static String stream(Context c, String id) {
        return base(c) + "/stream/" + id + "?t=" + enc(Prefs.token(c));
    }

    public static String img(Context c, String url, int px) {
        if (url == null || url.isEmpty()) return null;
        int s = px <= 200 ? 200 : (px <= 300 ? 300 : 500);
        return base(c) + "/img?s=" + s + "&t=" + enc(Prefs.token(c)) + "&u=" + enc(url);
    }

    public static void thumb(ImageView v, String url, int px, int radiusDp) {
        String u = img(v.getContext(), url, px);
        int r = Math.max(1, Ui.dp(v.getContext(), radiusDp));
        Glide.with(v).load(u).override(px, px).format(DecodeFormat.PREFER_RGB_565)
                .transform(new CenterCrop(), new RoundedCorners(r))
                .placeholder(R.drawable.ph).into(v);
    }

    /**
     * POST JSON generik, meniru pola get() supaya pesan galat dari server
     * (field "detail") tidak hilang. Tidak di-cache: responsnya bergantung pada
     * data pengguna.
     */
    private static <T> void post(Context c, String path, String json, final Class<T> cls,
                                 final Cb<T> cb) {
        final Context app = c.getApplicationContext();
        if (!Prefs.ready(app)) { cb.err("Server belum diatur (ketuk ikon gerigi di kanan atas)"); return; }
        final String url = base(app) + path;
        Request req;
        try {
            req = authed(new Request.Builder().url(url).header("X-Token", Prefs.token(app)),
                    app).post(okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse("application/json; charset=utf-8"),
                            json == null ? "{}" : json)).build();
        } catch (Exception e) {
            cb.err("Alamat server tidak valid");
            return;
        }
        call(req, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, "POST " + url, e);
                UI.post(() -> cb.err("Gagal: " + why));
            }

            @Override public void onResponse(Call call, Response resp) {
                try {
                    final String body = resp.body().string();
                    if (!resp.isSuccessful()) {
                        String msg = "Server merespons " + resp.code();
                        try {
                            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                            if (o.has("detail")) msg += ": " + o.get("detail").getAsString();
                        } catch (Exception ignored) { }
                        final String m = msg;
                        handleAuthFail(app, resp.code(), path);
                        UI.post(() -> cb.err(m));
                        return;
                    }
                    final T v = GSON.fromJson(body, cls);
                    UI.post(() -> cb.ok(v));
                } catch (Exception e) {
                    UI.post(() -> cb.err("Respons tidak valid"));
                } finally {
                    resp.close();
                }
            }
        });
    }

    private static <T> void get(Context c, String path, final long ttl, final Class<T> cls, final Cb<T> cb) {
        final Context app = c.getApplicationContext();
        if (!Prefs.ready(app)) {
            cb.err("Server belum diatur (ketuk ikon gerigi di kanan atas)");
            return;
        }
        final String url = base(app) + path;
        if (ttl > 0) {
            synchronized (CACHE) {
                Object[] h = CACHE.get(url);
                if (h != null && System.currentTimeMillis() - (Long) h[0] < ttl) {
                    cb.ok(GSON.fromJson((String) h[1], cls));
                    return;
                }
            }
        }
        Request req;
        try {
            req = authed(new Request.Builder().url(url).header("X-Token", Prefs.token(app)),
                    app).build();
        } catch (Exception e) {
            cb.err("Alamat server tidak valid");
            return;
        }
        call(req, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, "GET " + url, e);
                UI.post(() -> cb.err("Gagal: " + why));
            }

            @Override public void onResponse(Call call, Response resp) {
                try {
                    final String body = resp.body().string();
                    if (!resp.isSuccessful()) {
                        String msg = "Server merespons " + resp.code();
                        try {
                            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                            if (o.has("detail")) msg += ": " + o.get("detail").getAsString();
                        } catch (Exception ignored) { }
                        final String m = msg;
                        handleAuthFail(app, resp.code(), path);
                        UI.post(() -> cb.err(m));
                        return;
                    }
                    final T v = GSON.fromJson(body, cls);
                    if (ttl > 0) {
                        synchronized (CACHE) {
                            CACHE.put(url, new Object[]{System.currentTimeMillis(), body});
                        }
                    }
                    UI.post(() -> cb.ok(v));
                } catch (Exception e) {
                    UI.post(() -> cb.err("Respons tidak valid"));
                } finally {
                    resp.close();
                }
            }
        });
    }

    // ---------- akun: masuk, daftar, alias, keluar, adopsi profil lama ----------

    /** Masuk dengan username + sandi. Hasilnya token sesi. */
    public static void login(Context c, String user, String pass, final Cb<Session> cb) {
        post(c, "/auth/login", "{\"username\":\"" + esc(user) + "\",\"password\":\""
                + esc(pass) + "\"}", Session.class, cb);
    }

    /** Daftar akun baru. Butuh kunci master (PIN server). */
    public static void register(Context c, String user, String pass, String alias,
                                final Cb<Session> cb) {
        post(c, "/auth/register", "{\"username\":\"" + esc(user) + "\",\"password\":\""
                + esc(pass) + "\",\"alias\":\"" + esc(alias) + "\"}",
                Session.class, cb);
    }

    /** Pindahkan data profil lama ke akun ini. Butuh kunci master. */
    public static void adopt(Context c, String oldProfile, final Cb<Adopt> cb) {
        post(c, "/auth/adopt", "{\"old_profile\":\"" + esc(oldProfile) + "\"}",
                Adopt.class, cb);
    }

    /** Ganti alias singkat profil. */
    public static void setAlias(Context c, String alias, final Cb<Ok> cb) {
        post(c, "/auth/alias", "{\"alias\":\"" + esc(alias) + "\"}", Ok.class, cb);
    }

    public static void logout(Context c) {
        send(c, "POST", "/auth/logout", "{}", new Cb<Ok>() {
            @Override public void ok(Ok r) { }
            @Override public void err(String m) { }
        });
    }

    /** Cek cepat: apakah server hidup dan PIN diterima. */
    public static void ping(Context c, final Cb<String> cb) {
        final Context app = c.getApplicationContext();
        if (Prefs.server(app).isEmpty()) { cb.err("Alamat server masih kosong"); return; }
        Request req;
        try {
            req = authed(new Request.Builder().url(base(app) + "/health")
                    .header("X-Token", Prefs.token(app)), app).build();
        } catch (Exception e) { cb.err("Alamat server tidak valid"); return; }
        call(req, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, "PING " + base(app), e);
                UI.post(() -> cb.err("Gagal: " + why));
            }
            @Override public void onResponse(Call call, Response resp) {
                final int code = resp.code();
                resp.close();
                UI.post(() -> {
                    if (code == 200) cb.ok("Tersambung");
                    else if (code == 401) cb.err("PIN salah");
                    else cb.err("Server merespons " + code);
                });
            }
        });
    }

    /** Saran pencarian saat mengetik. */
    public static void suggest(Context c, String q, Cb<Suggest> cb) {
        get(c, "/suggest?q=" + enc(q), 300000, Suggest.class, cb);
    }


    /** POST/PATCH/DELETE dengan body JSON; hasil diabaikan kalau tidak perlu. */
    public static void send(Context c, String method, String path, String json, final Cb<Ok> cb) {
        final Context app = c.getApplicationContext();
        if (!Prefs.ready(app)) { cb.err("Server belum diatur"); return; }
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json; charset=utf-8"), json == null ? "{}" : json);
        Request.Builder rb;
        try {
            rb = authed(new Request.Builder().url(base(app) + path)
                    .header("X-Token", Prefs.token(app)), app);
        } catch (Exception e) { cb.err("Alamat server tidak valid"); return; }
        if ("DELETE".equals(method)) rb.delete(body);
        else if ("PATCH".equals(method)) rb.patch(body);
        else rb.post(body);
        call(rb.build(), new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, method + " " + path, e);
                UI.post(() -> cb.err(why));
            }
            @Override public void onResponse(Call call, Response resp) {
                try { resp.body().string(); } catch (Exception ignored) { }
                final int code = resp.code();
                resp.close();
                UI.post(() -> {
                    if (code >= 200 && code < 300) {
                        Ok o = new Ok(); o.ok = true; o.fav = true; cb.ok(o);
                    } else {
                        cb.err("Server merespons " + code);
                    }
                });
            }
        });
    }

    private static String json(Item it) {
        JsonObject o = new JsonObject();
        o.addProperty("videoId", it.id);
        o.addProperty("title", it.title);
        o.addProperty("subtitle", it.subtitle);
        o.addProperty("thumb", it.thumb);
        o.addProperty("dur", it.dur);
        return o.toString();
    }

    // ---- koleksi lokal ----
    public static void recs(Context c, Cb<Items> cb) {
        get(c, "/recs?limit=20", 0, Items.class, new Cb<Items>() {
            @Override public void ok(Items r) {
                if (APP != null) Log2.trace(APP, "recs ok n=" + (r == null || r.items == null ? 0 : r.items.size()));
                cb.ok(r);
            }
            @Override public void err(String m) {
                if (APP != null) Log2.trace(APP, "recs err " + m);
                cb.err(m);
            }
        });
    }

    public static void favs(Context c, Cb<Items> cb) { get(c, "/lib/favorites", 0, Items.class, cb); }

    public static void history(Context c, Cb<Items> cb) { get(c, "/lib/history", 0, Items.class, cb); }

    public static void playlists(Context c, Cb<Items> cb) { get(c, "/lib/playlists", 0, Items.class, cb); }

    public static void playlistTracks(Context c, String id, Cb<Detail> cb) {
        get(c, "/lib/playlists/" + id, 0, Detail.class, cb);
    }

    public static void favAdd(Context c, Item it, Cb<Ok> cb) {
        send(c, "POST", "/lib/fav", json(it), cb);
    }

    public static void favRemove(Context c, String id, Cb<Ok> cb) {
        send(c, "DELETE", "/lib/fav/" + id, "{}", cb);
    }

    public static void createPlaylist(Context c, String name, final Cb<Item> cb) {
        final Context app = c.getApplicationContext();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json; charset=utf-8"), o.toString());
        Request req;
        try {
            req = authed(new Request.Builder().url(base(app) + "/lib/playlists")
                    .header("X-Token", Prefs.token(app)), app).post(body).build();
        } catch (Exception e) { cb.err("Alamat server tidak valid"); return; }
        call(req, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, "createPlaylist", e);
                UI.post(() -> cb.err(why));
            }
            @Override public void onResponse(Call call, Response resp) {
                String text = "";
                try { text = resp.body().string(); } catch (Exception ignored) { }
                final int code = resp.code();
                resp.close();
                if (code >= 200 && code < 300) {
                    final Item it = GSON.fromJson(text, Item.class);
                    UI.post(() -> cb.ok(it));
                } else {
                    UI.post(() -> cb.err("Server merespons " + code));
                }
            }
        });
    }

    public static void renamePlaylist(Context c, String pid, String name, Cb<Ok> cb) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        send(c, "PATCH", "/lib/playlists/" + pid, o.toString(), cb);
    }

    public static void deletePlaylist(Context c, String pid, Cb<Ok> cb) {
        send(c, "DELETE", "/lib/playlists/" + pid, "{}", cb);
    }

    public static void addToPlaylist(Context c, String pid, Item it, Cb<Ok> cb) {
        send(c, "POST", "/lib/playlists/" + pid + "/items", json(it), cb);
    }

    public static void removeFromPlaylist(Context c, String pid, String vid, Cb<Ok> cb) {
        send(c, "DELETE", "/lib/playlists/" + pid + "/items/" + vid, "{}", cb);
    }

    public static void reportHistory(Context c, Item it) {
        send(c, "POST", "/lib/history", json(it), new Cb<Ok>() {
            @Override public void ok(Ok r) { }
            @Override public void err(String m) { }
        });
    }

    public static void home(Context c, Cb<Home> cb) { get(c, "/home", 300000, Home.class, cb); }

    public static void search(Context c, String q, String kind, Cb<Items> cb) {
        get(c, "/search?type=" + kind + "&q=" + enc(q), 60000, Items.class, cb);
    }

    public static void library(Context c, Cb<Items> cb) { get(c, "/library", 120000, Items.class, cb); }

    public static void detail(Context c, String kind, String id, Cb<Detail> cb) {
        get(c, "/detail/" + kind + "/" + enc(id), 300000, Detail.class, cb);
    }

    public static void radio(Context c, String id, Cb<Items> cb) { get(c, "/radio/" + id, 0, Items.class, cb); }

    public static void lyrics(Context c, String id, Cb<Lyrics> cb) {
        get(c, "/lyrics/" + id, 3600000, Lyrics.class, cb);
    }

    /** Minta server menyiapkan URL audio lagu berikutnya agar skip terasa instan. */
    public static void prefetch(Context c, String id) {
        if (!Prefs.ready(c)) return;
        try {
            Request r = new Request.Builder().url(base(c) + "/prefetch/" + id)
                    .header("X-Token", Prefs.token(c))
                    .header("X-Profile", Prefs.profile(c)).build();
            HTTP.newCall(r).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { }
                @Override public void onResponse(Call call, Response resp) { resp.close(); }
            });
        } catch (Exception ignored) { }
    }
}
