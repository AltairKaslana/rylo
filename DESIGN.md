# Desain Rylo

## Prinsip
HP KitKat hanya pemutar+UI. Server Armbian yang login dan bicara ke YouTube, sehingga masalah TLS 1.3, root CA, dan Play Services di KitKat tidak muncul. HP bicara HTTP biasa ke server.

## Server (`server/app`)
- `main.py` route + auth (header `X-Token` atau `?t=`), proxy `/stream` (Range, retry satu kali bila URL kedaluwarsa), `/img` (proxy+resize cover, cache disk), `/prefetch`.
- `catalog.py` pembungkus ytmusicapi; semua hasil dinormalisasi ke `{kind,id,title,subtitle,thumb,dur}` dengan kind: song|album|artist|playlist.
- `resolver.py` yt-dlp (format m4a/AAC), cache URL di memori ±5 jam, kunci per videoId.
- `config.py` environment: YTM_TOKEN, YTM_AUTH, YTM_COOKIES, YTM_URL_TTL, YTM_IMG_CACHE.
- Server tidak menyimpan state; antrean dan riwayat ada di HP.

## Kontrak API
| Path | Respons |
|---|---|
| GET /health | {ok, authed} |
| GET /home | {sections:[{title, items:[Item]}]} |
| GET /search?q=&type= | {items:[Item]} |
| GET /library | {items:[Item]} (butuh browser.json) |
| GET /detail/{album\|artist\|playlist}/{id} | {kind,id,title,subtitle,thumb,tracks:[Item]} |
| GET /radio/{videoId} | {items:[Item]} lagu serupa untuk autoplay |
| GET /lyrics/{videoId} | {lyrics} |
| GET /stream/{videoId} | audio (200/206, Range) |
| GET /img?u=&s= | JPEG persegi (host Google/YouTube saja) |
| GET /prefetch/{videoId} | {ok} menghangatkan cache URL |

## Aplikasi (`android/app`, paket com.example.ytmplayer)
- `Playback` singleton: ExoPlayer + antrean + autoplay (radio) + prefetch + lompat otomatis saat error.
- `PlaybackService`: foreground service, notifikasi MediaStyle, MediaSession.
- UI: `MainActivity` (tab Beranda/Cari/Pustaka + mini player), fragment Home/Search/Library/Detail, `PlayerActivity` (pemutar penuh: seek, shuffle, repeat, lirik, antrean).
- Ringan: tanpa Compose/Material; Glide RGB_565; cache respons Api 1-5 menit.
