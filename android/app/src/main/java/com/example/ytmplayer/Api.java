package com.example.ytmplayer;

import android.content.Context;
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

    /** Host publik -> alamat cadangan (HTTP polos) untuk HP yang TLS-nya tidak sanggup. */
    /**
     * Jalur cadangan TLS: stunnel di box memakai sertifikat RSA sendiri (dipin di Net.java).
     * Dipakai bila HTTPS Cloudflare gagal karena HP lama tidak mendukung cipher ECDSA.
     */
    private static final String BORE_HOST = "bore.pub";
    /** Port tunnel TLS, dicoba berurutan. 24193 = port aktif saat ini. */
    private static final int[] BORE_PORTS = {24193, 24191, 24192, 24194, 24195, 24190};

    private static boolean hasFallback(String host) {
        return "musik.altairk.my.id".equals(host) || BORE_HOST.equals(host);
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

    /** Cari port yang bisa dihubungi. Mengembalikan 0 kalau semua mati. */
    public static int probePort(Context c) {
        for (int p : BORE_PORTS) {
            try {
                Request r = new Request.Builder().url("https://" + BORE_HOST + ":" + p + "/health?t="
                        + enc(Prefs.token(c))).header("X-Profile", Prefs.profile(c)).build();
                Response resp = HTTP.newCall(r).execute();
                int code = resp.code();
                resp.close();
                if (code == 200) {
                    Prefs.setBorePort(c, p);
                    Prefs.setAltTls(c, true);
                    return p;
                }
            } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** Pindah ke port berikutnya (dipakai saat audio gagal). */
    public static int nextPort(Context c) {
        int cur = activePort(c);
        int idx = 0;
        for (int i = 0; i < BORE_PORTS.length; i++) {
            if (BORE_PORTS[i] == cur) { idx = i; break; }
        }
        int next = BORE_PORTS[(idx + 1) % BORE_PORTS.length];
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

    /** Jalankan permintaan; bila TLS gagal, ulangi sekali ke jalur TLS cadangan (sertifikat kita). */
    private static void call(final Request req, final Callback cb) {
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                if (APP != null) Log2.trace(APP, "fail proto=" + req.url().scheme()
                        + " tls=" + Net.provider() + " " + e.getClass().getSimpleName());

                String host = req.url().host();
                if (hostOf(Prefs.server(APP)).equals(host) && hasFallback(host) && tlsTrouble(e)) {
                    // domain gagal -> pindah ke tunnel dengan port terbaik
                    try {
                        int p = activePort(APP);
                        String url = "https://" + BORE_HOST + ":" + p + req.url().encodedPath()
                                + (req.url().encodedQuery() == null ? "" : "?" + req.url().encodedQuery());
                        if (APP != null) Log2.trace(APP, "tls fallback -> " + url);
                        Prefs.setAltTls(APP, true);
                        HTTP.newCall(req.newBuilder().url(url).build()).enqueue(cb);
                        return;
                    } catch (Throwable ignored) { }
                } else if (BORE_HOST.equals(host) && tlsTrouble(e)) {
                    // port mati -> cari port lain
                    int p = nextPort(APP);
                    try {
                        String url = "https://" + BORE_HOST + ":" + p + req.url().encodedPath()
                                + (req.url().encodedQuery() == null ? "" : "?" + req.url().encodedQuery());
                        if (APP != null) Log2.trace(APP, "port mati -> coba " + url);
                        HTTP.newCall(req.newBuilder().url(url).build()).enqueue(cb);
                        return;
                    } catch (Throwable ignored) { }
                }
                cb.onFailure(call, e);
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

    /** Alamat audio. Kalau perangkat sudah pernah gagal TLS, langsung pakai jalur HTTP cadangan. */
    /** Base URL efektif: jalur TLS cadangan dipakai bila sudah pernah diperlukan. */
    public static String base(Context c) {
        String srv = Prefs.server(c);
        if (!Prefs.altTls(c)) return srv;
        if (hasFallback(hostOf(srv))) return "https://" + BORE_HOST + ":" + activePort(c);
        return srv;
    }

    public static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
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
            req = new Request.Builder().url(url).header("X-Token", Prefs.token(app))
                    .header("X-Profile", Prefs.profile(app)).build();
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
                        String msg = "Server " + resp.code();
                        try {
                            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                            if (o.has("detail")) msg += ": " + o.get("detail").getAsString();
                        } catch (Exception ignored) { }
                        final String m = msg;
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

    /** Cek cepat: apakah server hidup dan PIN diterima. */
    public static void ping(Context c, final Cb<String> cb) {
        final Context app = c.getApplicationContext();
        if (Prefs.server(app).isEmpty()) { cb.err("Alamat server masih kosong"); return; }
        Request req;
        try {
            req = new Request.Builder().url(base(app) + "/health")
                    .header("X-Token", Prefs.token(app))
                    .header("X-Profile", Prefs.profile(app)).build();
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
                    else cb.err("Server " + code);
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
            rb = new Request.Builder().url(base(app) + path)
                    .header("X-Token", Prefs.token(app))
                    .header("X-Profile", Prefs.profile(app));
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
                        cb.err("Server " + code);
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
            req = new Request.Builder().url(base(app) + "/lib/playlists")
                    .header("X-Token", Prefs.token(app))
                    .header("X-Profile", Prefs.profile(app)).post(body).build();
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
                    UI.post(() -> cb.err("Server " + code));
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
