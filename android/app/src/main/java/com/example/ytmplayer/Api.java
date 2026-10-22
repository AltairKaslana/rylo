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

    public static class Items { public List<Item> items; }
    public static class Section { public String title; public List<Item> items; }
    public static class Home { public List<Section> sections; }
    public static class Detail { public String kind, id, title, subtitle, thumb; public List<Item> tracks; }
    public static class Lyrics { public String lyrics; }

    private static final OkHttpClient HTTP = buildClient();

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS);
        if (Net.socketFactory() != null && Net.trustManager() != null) {
            b.sslSocketFactory(Net.socketFactory(), Net.trustManager());
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

    public static String stream(Context c, String id) {
        return Prefs.server(c) + "/stream/" + id + "?t=" + enc(Prefs.token(c));
    }

    public static String img(Context c, String url, int px) {
        if (url == null || url.isEmpty()) return null;
        int s = px <= 200 ? 200 : (px <= 300 ? 300 : 500);
        return Prefs.server(c) + "/img?s=" + s + "&t=" + enc(Prefs.token(c)) + "&u=" + enc(url);
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
        final String url = Prefs.server(app) + path;
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
            req = new Request.Builder().url(url).header("X-Token", Prefs.token(app)).build();
        } catch (Exception e) {
            cb.err("Alamat server tidak valid");
            return;
        }
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, "PING " + Prefs.server(app), e);
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
            req = new Request.Builder().url(Prefs.server(app) + "/health")
                    .header("X-Token", Prefs.token(app)).build();
        } catch (Exception e) { cb.err("Alamat server tidak valid"); return; }
        HTTP.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String why = Log2.write(app, "GET " + url, e);
                UI.post(() -> cb.err("Tidak bisa terhubung: " + why));
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
            Request r = new Request.Builder().url(Prefs.server(c) + "/prefetch/" + id)
                    .header("X-Token", Prefs.token(c)).build();
            HTTP.newCall(r).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { }
                @Override public void onResponse(Call call, Response resp) { resp.close(); }
            });
        } catch (Exception ignored) { }
    }
}
