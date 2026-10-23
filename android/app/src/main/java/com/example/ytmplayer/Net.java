package com.example.ytmplayer;

import android.content.Context;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 4.4 tidak punya root CA Let's Encrypt (ISRG Root X1/X2) dan sebagian build
 * belum mengaktifkan TLS 1.2 secara default. Kelas ini menambal keduanya:
 *  - menambah ISRG Root X1 + X2 sebagai trust anchor tambahan (bukan trust-all)
 *  - memaksa protokol TLS 1.2 pada socket
 * Dipasang sekali di App.onCreate dan dipakai juga oleh OkHttp (Api).
 */
public final class Net {
    private static SSLSocketFactory factory;
    private static X509TrustManager trust;

    private Net() {}

    private static String provider = "bawaan KitKat";
    private static String lastError = "";

    public static String provider() { return provider; }

    public static String lastError() { return lastError; }

    public static synchronized void install(Context c) {
        if (factory != null) return;

        // Kumpulkan trust anchor tambahan (root publik modern + sertifikat relay sendiri).
        X509TrustManager extraTm = null;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int[] raws = {R.raw.isrg_x1, R.raw.isrg_x2, R.raw.gts_r1, R.raw.gts_r2, R.raw.ytm_server};
            for (int raw : raws) {
                InputStream in = c.getResources().openRawResource(raw);
                int n = 0;
                try {
                    for (Certificate cert : cf.generateCertificates(in)) {
                        ks.setCertificateEntry("ca" + raw + "_" + n, cert);
                        n++;
                    }
                } finally {
                    in.close();
                }
            }
            TrustManagerFactory extra = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            extra.init(ks);
            extraTm = firstX509(extra.getTrustManagers());
        } catch (Throwable t) {
            lastError = "keystore: " + t.getClass().getSimpleName();
        }

        X509TrustManager defaultTm = null;
        try {
            TrustManagerFactory def = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            def.init((KeyStore) null);
            defaultTm = firstX509(def.getTrustManagers());
        } catch (Throwable ignored) { }

        trust = new Composite(defaultTm, extraTm);

        // 1) Conscrypt: tumpukan TLS modern (TLS 1.2 + ECDSA) untuk Android lama.
        try {
            java.security.Security.insertProviderAt(
                    (java.security.Provider) Class.forName("org.conscrypt.OpenSSLProvider")
                            .getDeclaredConstructor().newInstance(), 1);
            SSLContext cc = SSLContext.getInstance("TLS", "Conscrypt");
            cc.init(null, new TrustManager[]{trust}, null);
            factory = new Tls12(cc.getSocketFactory());
            provider = "Conscrypt";
        } catch (Throwable t) {
            lastError += " | conscrypt: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        // 2) Cadangan: TLS 1.2 dari tumpukan bawaan + trust tambahan.
        if (factory == null) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLSv1.2");
                ctx.init(null, new TrustManager[]{trust}, null);
                factory = new Tls12(ctx.getSocketFactory());
                provider = "manual TLSv1.2";
                try { HttpsURLConnection.setDefaultSSLSocketFactory(factory); } catch (Throwable ignored) { }
            } catch (Throwable t) {
                lastError += " | manual: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                provider = "gagal";
                factory = null;
            }
        }
    }

    /** Verifier: terima host apa pun ASALKAN sertifikatnya persis sertifikat relay kita. */
    public static javax.net.ssl.HostnameVerifier pinnedVerifier() {
        return new javax.net.ssl.HostnameVerifier() {
            private final javax.net.ssl.HostnameVerifier dflt =
                    javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();

            @Override public boolean verify(String host, javax.net.ssl.SSLSession session) {
                try {
                    java.security.cert.Certificate[] chain = session.getPeerCertificates();
                    if (chain != null && chain.length > 0
                            && chain[0] instanceof X509Certificate) {
                        java.security.MessageDigest md =
                                java.security.MessageDigest.getInstance("SHA-256");
                        byte[] fp = md.digest(chain[0].getEncoded());
                        StringBuilder sb = new StringBuilder();
                        for (byte b : fp) sb.append(String.format("%02x", b));
                        if (PIN_FP.equals(sb.toString())) return true;
                    }
                } catch (Throwable ignored) { }
                try { return dflt.verify(host, session); } catch (Throwable ignored) { }
                return false;
            }
        };
    }

    /** SHA-256 sertifikat relay (hex, tanpa titik dua) - dipakai untuk pinning. */
    private static final String PIN_FP =
            "32d2762b0a17e800dd99a7d6f9637f08c847dca6c9ba2e000ada2a3d9966b1ea";

    public static SSLSocketFactory socketFactory() { return factory; }

    public static X509TrustManager trustManager() { return trust; }

    private static X509TrustManager firstX509(TrustManager[] tms) {
        if (tms != null) {
            for (TrustManager tm : tms) {
                if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
            }
        }
        return null;
    }

    /** Coba validasi normal dulu; kalau gagal, coba pakai trust anchor tambahan. */
    private static final class Composite implements X509TrustManager {
        private final X509TrustManager a, b;

        Composite(X509TrustManager a, X509TrustManager b) {
            this.a = a;
            this.b = b;
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (a != null) a.checkClientTrusted(chain, authType);
            else if (b != null) b.checkClientTrusted(chain, authType);
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (a != null) {
                try {
                    a.checkServerTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException ignored) { }
            }
            if (b != null) {
                b.checkServerTrusted(chain, authType);
                return;
            }
            throw new java.security.cert.CertificateException("tidak ada trust anchor");
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<X509Certificate>();
            if (a != null) {
                X509Certificate[] t = a.getAcceptedIssuers();
                if (t != null) for (X509Certificate x : t) all.add(x);
            }
            if (b != null) {
                X509Certificate[] t = b.getAcceptedIssuers();
                if (t != null) for (X509Certificate x : t) all.add(x);
            }
            return all.toArray(new X509Certificate[all.size()]);
        }
    }

    /** Bungkus factory: aktifkan TLS 1.2 di setiap socket. */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory inner;

        Tls12(SSLSocketFactory inner) { this.inner = inner; }

        private Socket fix(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                try {
                    ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
                } catch (Throwable ignored) { }
                try {
                    // Aktifkan cipher modern (ECDSA & RSA). Bila tidak ada yang cocok, biarkan bawaan.
                    java.util.List<String> on = new java.util.ArrayList<String>();
                    for (String c : ssl.getSupportedCipherSuites()) {
                        if (c.startsWith("TLS_ECDHE_ECDSA_") || c.startsWith("TLS_ECDHE_RSA_")
                                || c.startsWith("TLS_AES_")) on.add(c);
                    }
                    if (!on.isEmpty()) ssl.setEnabledCipherSuites(on.toArray(new String[on.size()]));
                } catch (Throwable ignored) { }
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return inner.getDefaultCipherSuites(); }

        @Override public String[] getSupportedCipherSuites() { return inner.getSupportedCipherSuites(); }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return fix(inner.createSocket(s, host, port, autoClose));
        }

        @Override public Socket createSocket(String host, int port)
                throws java.io.IOException {
            return fix(inner.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws java.io.IOException {
            return fix(inner.createSocket(host, port, localHost, localPort));
        }

        @Override public Socket createSocket(InetAddress host, int port) throws java.io.IOException {
            return fix(inner.createSocket(host, port));
        }

        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                             int localPort) throws java.io.IOException {
            return fix(inner.createSocket(address, port, localAddress, localPort));
        }
    }
}
