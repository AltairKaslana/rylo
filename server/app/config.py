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
