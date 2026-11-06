# Rylo untuk HP KitKat — Panduan Pemasangan

```
HP KitKat (app Android, minSdk 19) --HTTP--> Armbian (server/ ytm-relay) --HTTPS--> YouTube Music
```
Isi paket: `server/` (FastAPI), `android/` (proyek Android Studio), `DESIGN.md` (arsitektur & API).

## Status yang jujur
- Server: sudah dites (auth, validasi, normalisasi data). Panggilan nyata ke YouTube belum bisa dites dari lingkungan pembuatnya.
- Aplikasi Android: **belum pernah dikompilasi/dijalankan**. Hanya sintaks Java dan XML yang diperiksa. Bila build pertama error, itu perbaikan kecil (import/versi library).
- Memakai jalur tidak resmi: melanggar ketentuan layanan, bisa rusak saat YouTube berubah, akun bisa dibatasi.

## Prasyarat
- Armbian/Linux ARM64 dengan Python 3.9+ dan akses internet. Tidak butuh ffmpeg.
- PC dengan Android Studio (Electric Eel atau lebih baru, JDK 11+) untuk membangun APK.
- HP KitKat dan Armbian di Wi-Fi/LAN yang sama.

## Langkah 1 — Pasang server
```bash
# 1. buat user & salin file
sudo useradd -r -m -d /opt/ytm-relay ytm
sudo cp -r server/. /opt/ytm-relay/ && sudo chown -R ytm: /opt/ytm-relay
cd /opt/ytm-relay

# 2. virtualenv + dependensi
sudo -u ytm python3 -m venv venv
sudo -u ytm venv/bin/pip install -r requirements.txt

# 3. buat token acak, lalu tempel ke ytm-relay.service (baris YTM_TOKEN=)
openssl rand -hex 24

# 4. (opsional, untuk tab Pustaka) login ytmusicapi
sudo -u ytm venv/bin/ytmusicapi browser     # ikuti petunjuk: tempel request headers dari music.youtube.com
sudo chmod 600 /opt/ytm-relay/browser.json
# gunakan akun YouTube khusus bila ingin meminimalkan risiko

# 5. jalankan sebagai service
sudo cp ytm-relay.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now ytm-relay
sudo systemctl status ytm-relay
```
Cari IP Armbian: `hostname -I`. Jika memakai ufw: `sudo ufw allow from 192.168.0.0/16 to any port 8000`.

### Tes server bertahap (ganti IP dan TOKEN)
```bash
curl "http://IP:8000/health?t=TOKEN"                        # {"ok":true,...}
curl "http://IP:8000/search?q=coldplay&type=songs&t=TOKEN"  # daftar lagu (catat satu "id")
curl -I "http://IP:8000/stream/VIDEO_ID?t=TOKEN"            # harus 200 dan audio/mp4
curl -H "Range: bytes=0-99" -o /dev/null -w "%{http_code}\n" "http://IP:8000/stream/VIDEO_ID?t=TOKEN"   # harus 206
```
Atau buka URL `/stream/...` di VLC pada PC. Jangan lanjut ke langkah 2 sebelum tes ini lolos.

## Langkah 2 — Bangun APK
1. Buka folder `android/` di Android Studio, tunggu Gradle sync (Studio membuat `gradlew` otomatis).
2. Setujui instalasi SDK Platform 33 jika diminta.
3. Build > Build Bundle(s)/APK(s) > Build APK(s). Hasil: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Langkah 3 — Pasang di HP KitKat
1. Pengaturan > Keamanan > aktifkan "Sumber tidak dikenal".
2. Salin APK ke HP (USB/kartu SD/`adb install app-debug.apk`) lalu pasang.
3. Buka app. Alamat server sudah tertanam (tidak perlu diketik); untuk mengganti alamat atau mengisi kunci master, tekan lama judul "Masuk" 3 detik (jalur admin). Kunci master hanya diperlukan untuk mengadopsi profil lama.

## Pemecahan masalah
| Gejala | Penyebab/solusi |
|---|---|
| "Tidak bisa terhubung ke server" | IP/port salah, beda jaringan, firewall; tes `curl` dari PC |
| "Server 401: Token salah" | Token di app tidak sama dengan `YTM_TOKEN` |
| Beranda/pencarian error 502 | Perbarui: `venv/bin/pip install -U ytmusicapi yt-dlp`, restart service |
| Lagu tidak mau diputar / 502 yt-dlp | Perbarui yt-dlp; jika masih gagal set `YTM_COOKIES=/opt/ytm-relay/cookies.txt` |
| Pustaka: "browser.json belum dipasang" | Jalankan `ytmusicapi browser` (langkah 1.4) lalu restart service |
| Cover kosong | Cek `journalctl -u ytm-relay`; folder `cache/img` harus bisa ditulis user `ytm` |
| Musik berhenti saat layar mati | Matikan penghemat baterai untuk app ini di pengaturan HP |
| Build gagal | Kirim pesan error lengkapnya; biasanya import atau versi library |

Log server: `journalctl -u ytm-relay -f`. Pembaruan berkala (cron): `0 4 * * 1 root /opt/ytm-relay/venv/bin/pip install -U yt-dlp ytmusicapi && systemctl restart ytm-relay`.
Cache cover tumbuh perlahan; bersihkan kapan saja dengan `rm -rf /opt/ytm-relay/cache/img/*`.

## Akses dari luar rumah (opsional)
Jangan membuka port 8000 ke internet. KitKat tidak mendukung WireGuard/Tailscale modern, jadi pakai OpenVPN lama ke jaringan rumah, lalu alamat server tetap IP LAN. HTTPS langsung ke domain biasanya gagal di KitKat (root CA usang).

## Bagikan ke orang lain (mode publik)
Server dengan `YTM_PUBLIC=1` mendaftarkan pengguna memakai **kode undangan** — bukan PIN master. Satu kode = satu orang, sekali pakai, berlaku 14 hari.

Membuat kode (di box, sebagai root; kode mentah hanya muncul SEKALI — server hanya menyimpan hash):
```bash
curl -X POST http://127.0.0.1:8000/auth/invite \
  -H "X-Token: <YTM_TOKEN dari /opt/ytm-relay/env>" \
  -H 'Content-Type: application/json' \
  -d '{"note":"nama orang","days":14}'
```
Cek status semua kode: `curl http://127.0.0.1:8000/auth/invites -H "X-Token: <YTM_TOKEN>"` — menampilkan kode mana masih kosong, sudah dipakai siapa, dan kapan kedaluwarsa.

Yang dibagikan ke tiap orang: (1) APK dari https://github.com/AltairKaslana/rylo/releases/latest, (2) satu kode undangan. Alamat server sudah tertanam di aplikasi.

Alur mereka: pasang APK → *Belum punya akun? Daftar* → username + sandi (min 6 karakter) + kode → langsung masuk. Opsional: tombol **Hubungkan YouTube Music** menautkan akun Google mereka sendiri lewat kode `youtube.tv/activate` — tanpa itu pun pencarian, radio, dan pemutaran tetap jalan. Kuota per akun: 2 lagu bersamaan, 60 pencarian/10 menit; pendaftaran dibatasi 5 per IP per jam.

Catatan jujur: ini jalur tidak resmi — melanggar ketentuan layanan YouTube dan bisa berhenti bekerja kapan saja. Setiap orang memakai akun Google-nya sendiri, jadi risiko pembatasan jatuh ke akun masing-masing.

## Batasan
Tidak ada mode offline, ada jeda 1-3 detik saat memulai lagu yang belum di-prefetch, dan audio tidak disimpan.
