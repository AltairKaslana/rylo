import os

TOKEN = os.getenv("YTM_TOKEN", "")                      # wajib (token panjang)
PIN = os.getenv("YTM_PIN", "")                          # opsional: PIN pendek untuk HP (lebih mudah diketik)
AUTH_FILE = os.getenv("YTM_AUTH", "browser.json")       # opsional (untuk Library)
COOKIES = os.getenv("YTM_COOKIES", "")                  # opsional: cookies.txt untuk yt-dlp
URL_TTL = int(os.getenv("YTM_URL_TTL", str(5 * 3600)))  # umur cache URL audio (detik)
IMG_CACHE = os.getenv("YTM_IMG_CACHE", os.path.join(os.getcwd(), "cache", "img"))
OAUTH_ID = os.getenv("YTM_OAUTH_ID", "")               # client_id OAuth (TVs and Limited Input)
OAUTH_SECRET = os.getenv("YTM_OAUTH_SECRET", "")               # client_secret pasangannya
PREFETCH = int(os.getenv("YTM_PREFETCH", "3"))                 # berapa lagu teratas di-preload URL audio-nya
PREFETCH_WORKERS = int(os.getenv("YTM_PREFETCH_WORKERS", "2"))  # batas ekstraksi yt-dlp paralel

# ---------------- mode publik ----------------
# PUBLIC=1: pendaftaran memakai kode undangan (bukan PIN master), dan akun tanpa
# tautan Google tetap boleh memakai katalog umum. PIN master hanya untuk admin.
PUBLIC = os.getenv("YTM_PUBLIC", "0") == "1"
INVITE_DAYS = int(os.getenv("YTM_INVITE_DAYS", "14"))   # masa berlaku kode undangan

# Kuota per akun. Angka ini menjaga box 1,9 GB tetap waras saat dipakai beberapa orang:
# dua stream serentak sudah cukup untuk satu orang (satu lagu + satu pramuat),
# dan 60 pencarian per 10 menit jauh di atas pemakaian manusia normal.
MAX_STREAMS = int(os.getenv("YTM_MAX_STREAMS", "2"))
SEARCH_LIMIT = int(os.getenv("YTM_SEARCH_LIMIT", "60"))
SEARCH_WINDOW = int(os.getenv("YTM_SEARCH_WINDOW", "600"))
SIGNUP_PER_HOUR = int(os.getenv("YTM_SIGNUP_PER_HOUR", "5"))   # per IP

# AUTH_DIR: kredensial Google PER AKUN, satu berkas per profil, izin 600.
# AUTH_FILE lama (satu berkas untuk semua orang) tetap dibaca sebagai pemilik.
AUTH_DIR = os.getenv("YTM_AUTH_DIR", "/opt/ytm-relay/auth")

