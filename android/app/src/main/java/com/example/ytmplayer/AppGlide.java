package com.example.ytmplayer;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

/** Glide memakai klien OkHttp aplikasi: koneksi TLS dipakai ulang, cover muncul lebih cepat. */
@GlideModule
public class AppGlide extends AppGlideModule {
    @Override public void registerComponents(@NonNull Context c, @NonNull Glide g,
                                             @NonNull Registry r) {
        r.replace(GlideUrl.class, InputStream.class,
                new OkHttpUrlLoader.Factory(Api.client()));
    }
}
