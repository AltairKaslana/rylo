import hashlib
import io
import os
import re
import secrets
import threading
import time
from urllib.parse import urlparse

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse, Response, StreamingResponse
from PIL import Image, ImageOps
from starlette.background import BackgroundTask

from . import catalog, config, resolver
from . import library as lib

lib.init()

if not config.TOKEN:
    raise RuntimeError("Environment variable YTM_TOKEN belum diisi")


def _same(a: str, b: str) -> bool:
    return bool(a) and bool(b) and secrets.compare_digest(a.encode(), b.encode())


_FAILS = {}
_FAIL_LIMIT = 8          # salah PIN sebanyak ini...
_FAIL_WINDOW = 600       # ...dalam 10 menit
_BLOCK_TIME = 600        # ...maka diblokir 10 menit


def _rate_check(ip: str) -> bool:
    """True kalau IP masih boleh mencoba."""
    rec = _FAILS.get(ip)
    if not rec:
        return True
    if rec.get("until", 0) > time.time():
        return False
    return True


def _rate_fail(ip: str):
    now = time.time()
    rec = _FAILS.setdefault(ip, {"n": 0, "first": now, "until": 0})
    if now - rec["first"] > _FAIL_WINDOW:
        rec["n"], rec["first"] = 0, now
    rec["n"] += 1
    if rec["n"] >= _FAIL_LIMIT:
        rec["until"] = now + _BLOCK_TIME
        rec["n"] = 0


def auth(request: Request, x_token: str = Header(None), t: str = Query(None)):
    """Terima token panjang (YTM_TOKEN) atau PIN pendek (YTM_PIN) - PIN untuk diketik di HP."""
    ip = (request.client.host if request.client else "?") or "?"
    if not _rate_check(ip):
        raise HTTPException(429, "Terlalu banyak percobaan, coba lagi 10 menit")
    given = x_token or t or ""
    if _same(given, config.TOKEN) or _same(given, config.PIN):
        _FAILS.pop(ip, None)
        return
    _rate_fail(ip)
    raise HTTPException(401, "Token/PIN salah")


app = FastAPI(dependencies=[Depends(auth)], docs_url=None, redoc_url=None, openapi_url=None)
VID = re.compile(r"^[\w-]{11}$")
IMG_HOSTS = ("googleusercontent.com", "ggpht.com", "ytimg.com")
os.makedirs(config.IMG_CACHE, exist_ok=True)


def call(fn, *args):
    try:
        return fn(*args)
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise HTTPException(502, f"{type(e).__name__}: {e}"[:300])


def check_vid(v):
    if not VID.match(v):
        raise HTTPException(400, "videoId tidak valid")


@app.get("/health")
def health():
    return {"ok": True, "authed": catalog.authed()}


@app.get("/home")
def home():
    return {"sections": call(catalog.home)}


@app.get("/search")
def search(q: str, type: str = "songs"):
    return {"items": call(catalog.search, q, type)}


@app.get("/auth-status")
def auth_status():
    """Status login yang jujur: file ada, dan sesinya masih hidup atau sudah dibatalkan Google."""
    ok = catalog.authed()
    return {"authed": ok, "logged_in": catalog.session_alive() if ok else False}


@app.get("/suggest")
def suggest(q: str):
    if len(q.strip()) < 2:
        return {"items": []}
    return {"items": call(catalog.suggest, q)}


@app.get("/library")
def library():
    if not catalog.authed():
        raise HTTPException(403, "browser.json belum dipasang (Library butuh login)")
    return {"items": call(catalog.library)}


@app.get("/detail/{kind}/{iid}")
def detail(kind: str, iid: str):
    if kind not in ("album", "artist", "playlist"):
        raise HTTPException(400, "kind tidak valid")
    return call(catalog.detail, kind, iid)


@app.get("/radio/{vid}")
def radio(vid: str):
    check_vid(vid)
    return {"items": call(catalog.radio, vid)}


@app.get("/lyrics/{vid}")
def lyrics(vid: str):
    check_vid(vid)
    return {"lyrics": call(catalog.lyrics, vid) or ""}


@app.get("/prefetch/{vid}")
def prefetch(vid: str):
    check_vid(vid)

    def work():
        try:
            resolver.get(vid)
        except Exception:  # noqa: BLE001
            pass

    threading.Thread(target=work, daemon=True).start()
    return {"ok": True}


def _resize(data: bytes, s: int) -> bytes:
    im = Image.open(io.BytesIO(data)).convert("RGB")
    im = ImageOps.fit(im, (s, s), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=82, optimize=True)
    return out.getvalue()


@app.get("/img")
async def img(u: str, s: int = 300):
    p = urlparse(u)
    host = p.hostname or ""
    if p.scheme != "https" or not any(host == h or host.endswith("." + h) for h in IMG_HOSTS):
        raise HTTPException(400, "host gambar tidak diizinkan")
    s = max(64, min(s, 600))
    if "googleusercontent.com" in host or "ggpht.com" in host:
        u = re.sub(r"=w\d+-h\d+.*$", f"=w{s}-h{s}-l90-rj", u)
    path = os.path.join(config.IMG_CACHE, hashlib.sha1(f"{u}|{s}".encode()).hexdigest() + ".jpg")
    if not os.path.exists(path):
        try:
            async with httpx.AsyncClient(timeout=15, follow_redirects=True) as c:
                r = await c.get(u)
                r.raise_for_status()
            data = await run_in_threadpool(_resize, r.content, s)
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"gambar gagal: {type(e).__name__}")
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    return FileResponse(path, media_type="image/jpeg",
                        headers={"Cache-Control": "public, max-age=604800"})


@app.head("/stream/{vid}")
def stream_head(vid: str):
    """HEAD: banyak pemutar/tool memeriksa dukungan Range lebih dulu."""
    check_vid(vid)
    return Response(status_code=200, media_type="audio/mp4", headers={"Accept-Ranges": "bytes"})


@app.get("/stream/{vid}")
async def stream(vid: str, request: Request):
    """Proxy audio apa adanya (tanpa disimpan), dengan dukungan HTTP Range untuk seek."""
    check_vid(vid)
    rng = request.headers.get("range")
    up = client = None
    for attempt in (0, 1):
        try:
            info = await run_in_threadpool(resolver.get, vid, attempt == 1)
        except Exception as e:  # noqa: BLE001
            raise HTTPException(502, f"yt-dlp gagal: {str(e)[:200]}")
        headers = {k: v for k, v in info["headers"].items()
                   if k.lower() not in ("accept-encoding", "range")}
        if rng:
            headers["Range"] = rng
        client = httpx.AsyncClient(timeout=httpx.Timeout(15.0, read=60.0), follow_redirects=True)
        try:
            up = await client.send(client.build_request("GET", info["url"], headers=headers),
                                   stream=True)
        except httpx.HTTPError as e:
            await client.aclose()
            if attempt == 1:
                raise HTTPException(502, f"Gagal menghubungi sumber audio: {e}")
            continue
        except BaseException:
            await client.aclose()
            raise
        if up.status_code in (403, 404, 410) and attempt == 0:
            await up.aclose()
            await client.aclose()
            continue  # URL kedaluwarsa: resolve ulang sekali
        break

    keep = ("content-type", "content-length", "content-range", "accept-ranges")
    out = {k: v for k, v in up.headers.items() if k.lower() in keep}

    async def close():
        await up.aclose()
        await client.aclose()

    return StreamingResponse(up.aiter_raw(), status_code=up.status_code, headers=out,
                             background=BackgroundTask(close))

SETUP_HTML = """<!doctype html><html><head><meta charset="utf-8">
<title>Login YouTube Music</title><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
 body{background:#0d1117;color:#c9d1d9;font:15px/1.6 system-ui,Segoe UI,sans-serif;margin:0;padding:20px;max-width:760px}
 h1{font-size:19px;margin:0 0 6px} h2{font-size:15px;margin:22px 0 6px;color:#58a6ff}
 code,pre{background:#161b22;border:1px solid #30363d;border-radius:6px}
 pre{padding:10px;overflow:auto;font-size:12.5px}
 textarea{width:100%;height:170px;background:#0d1117;color:#c9d1d9;border:1px solid #30363d;border-radius:8px;padding:10px;font:12.5px/1.5 monospace}
 button{background:#238636;color:#fff;border:0;border-radius:8px;padding:11px 18px;font-size:15px;margin-top:10px}
 .ok{color:#3fb950}.bad{color:#f85149} .muted{color:#8b949e;font-size:13px}
 ol{padding-left:20px} li{margin:4px 0}
</style></head><body>
<h1>Login YouTube Music</h1>
<p class="muted">Sekali saja. Setelah tersimpan, tab Pustaka dan beranda personal akan aktif.</p>
<h2>1. Ambil header dari browser (di PC)</h2>
<ol>
<li>Buka <b>music.youtube.com</b> di Chrome/Firefox, pastikan avatar akun muncul di kanan atas.</li>
<li>Tekan <b>F12</b> &rarr; tab <b>Network</b> &rarr; pilih filter <b>Fetch/XHR</b>.</li>
<li>Tekan <b>F5</b>, lalu klik request bernama <b>browse</b> atau <b>account_menu</b>.</li>
<li>Panel kanan: tab <b>Headers</b> &rarr; <b>Request Headers</b> &rarr; klik kanan &rarr; <b>Copy request headers</b>.</li>
<li>Pastikan blok itu memuat baris <code>Authorization: SAPISIDHASH *** dan <code>Cookie: ... SID=...</code>.</li>
</ol>
<h2>2. Tempel di sini</h2>
<textarea id="h" placeholder="cookie: HSID=...; SSID=...; SID=...&#10;user-agent: Mozilla/5.0 ...&#10;x-goog-authuser: 0"></textarea>
<div id="r" class="muted"></div>
<button onclick="save()">Simpan</button>
<h2>Catatan</h2>
<p class="muted">Header ini setara kunci akun. Disimpan hanya di server ini
(<code>/opt/ytm-relay/browser.json</code>, izin 600) dan tidak pernah dikirim ke tempat lain.
Pakai akun khusus bila ingin memisahkan risiko.</p>
<script>
async function save(){
  const t=document.getElementById('h').value.trim();
  const r=document.getElementById('r');
  if(!t){ r.className='bad'; r.textContent='Masih kosong.'; return; }
  r.className='muted'; r.textContent='Menyimpan...';
  try{
    const res=await fetch('/setup?t='+encodeURIComponent(location.search.replace('?t=','')),{method:'POST',headers:{'Content-Type':'text/plain'},body:t});
    const j=await res.json();
    if(res.ok){ r.className='ok'; r.textContent='Berhasil. authed='+j.authed+' - buka tab Pustaka di aplikasi.'; }
    else { r.className='bad'; r.textContent='Gagal: '+(j.detail||res.status); }
  }catch(e){ r.className='bad'; r.textContent='Gagal menghubungi server.'; }
}
</script></body></html>"""


@app.get("/setup", response_class=HTMLResponse)
def setup_page():
    return SETUP_HTML


SAPISID_RE = re.compile(r"(?:__Secure-3PAPISID|SAPISID|__Secure-1PAPISID)=([^;\s]+)")


def _authorization(raw: str) -> str:
    """Header Authorization: SAPISIDHASH *** dipakai ytmusicapi untuk mendeteksi login.

    Request dokumen (GET /) tidak membawa header ini, jadi dihitung sendiri dari cookie:
    sha1("<ts> <sapisid> https://music.youtube.com").
    """
    m = SAPISID_RE.search(raw)
    if not m:
        return ""
    ts = int(time.time())
    digest = hashlib.sha1(
        f"{ts} {m.group(1)} https://music.youtube.com".encode()).hexdigest()
    return f"SAPISIDHASH {ts}_{digest}"


def _logged_in(cookie: str, raw: str) -> bool:
    """Cek jujur: apakah cookie ini benar-benar sesi login YouTube Music."""
    ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    for line in raw.splitlines():
        if line.lower().startswith("user-agent:"):
            ua = line.split(":", 1)[1].strip()
            break
    try:
        import httpx
        r = httpx.get("https://music.youtube.com/", timeout=25, follow_redirects=True,
                      headers={"Cookie": cookie, "User-Agent": ua,
                               "Accept-Language": "en-US,en;q=0.9"})
        return '"LOGGED_IN": true' in r.text or '"LOGGED_IN":true' in r.text
    except Exception:  # noqa: BLE001
        return False


@app.post("/setup")
async def setup_save(request: Request):
    """Simpan browser.json dari header yang ditempel pengguna (tidak lewat chat)."""
    raw = (await request.body()).decode("utf-8", "replace").strip()
    if "cookie" not in raw.lower():
        raise HTTPException(400, "Header harus memuat baris Cookie")
    # Request dokumen tidak membawa header-header ini; lengkapi supaya ytmusicapi menerimanya.
    if "authorization:" not in raw.lower():
        authz = _authorization(raw)
        if not authz:
            raise HTTPException(400, "Cookie tidak memuat SAPISID - pastikan sudah login di music.youtube.com")
        raw += "\nAuthorization: " + authz
    if "x-goog-authuser" not in raw.lower():
        raw += "\nX-Goog-AuthUser: 0"
    if "origin:" not in raw.lower():
        raw += "\nOrigin: https://music.youtube.com"
    # Verifikasi dulu: cookie yang benar-benar login membuat YouTube mengembalikan LOGGED_IN true.
    cookie = ""
    for line in raw.splitlines():
        if line.lower().startswith("cookie:"):
            cookie = line.split(":", 1)[1].strip()
            break
    if not cookie:
        raise HTTPException(400, "Baris Cookie tidak ditemukan")
    if not _logged_in(cookie, raw):
        raise HTTPException(400,
                            "Cookie tidak valid atau sesi sudah berakhir - YouTube menyebut belum login. "
                            "Salin ulang header dari request XHR 'browse' saat avatar akun terlihat di kanan atas.")

    target = config.AUTH_FILE if os.path.isabs(config.AUTH_FILE) else os.path.join(os.getcwd(), config.AUTH_FILE)
    try:
        from ytmusicapi import setup as ytm_setup
        ytm_setup(filepath=target, headers_raw=raw)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, f"Header tidak valid: {type(e).__name__}: {str(e)[:200]}")
    try:
        os.chmod(target, 0o600)
    except OSError:
        pass
    catalog.reset()
    return {"ok": True, "authed": catalog.authed()}

# ---------------- koleksi lokal (tanpa akun YouTube) ----------------

def prof(x_profile: str = Header(None)) -> str:
    """Profil perangkat: header X-Profile. Tanpa header -> 'default'."""
    return lib.clean_profile(x_profile)


@app.get("/profile")
def profile_info(profile: str = Depends(prof)):
    return lib.touch_profile(profile)


@app.post("/profile")
async def profile_rename(request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    return lib.touch_profile(profile, (body.get("name") or "").strip()[:40] or None)


@app.get("/lib/stats")
def lib_stats(profile: str = Depends(prof)):
    return lib.stats(profile)


@app.get("/lib/favorites")
def lib_favs(profile: str = Depends(prof)):
    return {"items": lib.favs(profile)}


@app.get("/lib/fav/{vid}")
def lib_fav_status(vid: str, profile: str = Depends(prof)):
    check_vid(vid)
    return {"fav": lib.fav_has(profile, vid)}


@app.post("/lib/fav")
async def lib_fav_add(request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    if not lib.fav_add(profile, body):
        raise HTTPException(400, "videoId wajib")
    return {"ok": True, "fav": True}


@app.delete("/lib/fav/{vid}")
def lib_fav_del(vid: str, profile: str = Depends(prof)):
    check_vid(vid)
    lib.fav_remove(profile, vid)
    return {"ok": True, "fav": False}


@app.get("/lib/history")
def lib_history(limit: int = 100, profile: str = Depends(prof)):
    return {"items": lib.history(profile, limit)}


@app.post("/lib/history")
async def lib_history_add(request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    if not lib.history_add(profile, body):
        raise HTTPException(400, "videoId wajib")
    return {"ok": True}


@app.delete("/lib/history")
def lib_history_clear(profile: str = Depends(prof)):
    lib.history_clear(profile)
    return {"ok": True}


@app.get("/lib/playlists")
def lib_playlists(profile: str = Depends(prof)):
    return {"items": lib.playlists(profile)}


@app.post("/lib/playlists")
async def lib_playlist_create(request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    pl = lib.playlist_create(profile, body.get("name"))
    if not pl:
        raise HTTPException(400, "nama playlist kosong")
    return pl


@app.patch("/lib/playlists/{pid}")
async def lib_playlist_rename(pid: int, request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    if not lib.playlist_rename(profile, pid, body.get("name")):
        raise HTTPException(400, "gagal ganti nama")
    return {"ok": True}


@app.delete("/lib/playlists/{pid}")
def lib_playlist_delete(pid: int, profile: str = Depends(prof)):
    lib.playlist_delete(profile, pid)
    return {"ok": True}


@app.get("/lib/playlists/{pid}")
def lib_playlist_items(pid: int, profile: str = Depends(prof)):
    name = None
    for pl in lib.playlists(profile):
        if pl["id"] == str(pid):
            name = pl["title"]
            break
    return {"kind": "playlist", "id": str(pid), "title": name or "Playlist",
            "subtitle": "lokal", "thumb": "", "tracks": lib.playlist_items(profile, pid)}


@app.post("/lib/playlists/{pid}/items")
async def lib_playlist_add(pid: int, request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    if not lib.playlist_add(profile, pid, body):
        raise HTTPException(400, "videoId wajib / playlist bukan milik profil ini")
    return {"ok": True}


@app.delete("/lib/playlists/{pid}/items/{vid}")
def lib_playlist_remove(pid: int, vid: str, profile: str = Depends(prof)):
    check_vid(vid)
    lib.playlist_remove(profile, pid, vid)
    return {"ok": True}


@app.get("/lib/search")
def lib_search(q: str, profile: str = Depends(prof)):
    return {"items": lib.search(profile, q)}


@app.get("/lib/recent-searches")
def recent_searches(profile: str = Depends(prof), limit: int = 12):
    """Pencarian terakhir, terbaru lebih dulu (dipakai tab Pencarian)."""
    return {"items": lib.searches(profile, limit)}


@app.post("/lib/recent-searches")
def remember_search(q: str, kind: str = "songs", profile: str = Depends(prof)):
    lib.search_add(profile, q, kind)
    return {"ok": True}


@app.delete("/lib/recent-searches")
def forget_search(q: str = "", profile: str = Depends(prof)):
    """q kosong = hapus semua."""
    if q.strip():
        lib.search_remove(profile, q)
    else:
        lib.search_clear(profile)
    return {"ok": True}


@app.get("/recs")
def recs(profile: str = Depends(prof), limit: int = 20):
    """Rekomendasi berbasis kebiasaan: benih dari favorit + riwayat, lanjut ke radio lagu itu."""
    seeds = []
    for it in lib.favs(profile)[:4] + lib.history(profile, 12):
        if it["id"] not in seeds:
            seeds.append(it["id"])
        if len(seeds) >= 5:
            break
    recent = {x["id"] for x in lib.history(profile, 60)}
    out, have = [], set()
    for vid in seeds:
        try:
            for it in catalog.radio(vid):
                if it["id"] in have or it["id"] in recent or it["id"] in seeds:
                    continue
                have.add(it["id"])
                out.append(it)
                if len(out) >= limit:
                    break
        except Exception:  # noqa: BLE001
            continue
        if len(out) >= limit:
            break
    if not out:
        # cadangan: ambil dari beranda umum
        try:
            for sec in catalog.home():
                for it in sec.get("items", []):
                    if it.get("kind") == "song" and it["id"] not in have:
                        have.add(it["id"])
                        out.append(it)
                        if len(out) >= limit:
                            break
                if len(out) >= limit:
                    break
        except Exception:  # noqa: BLE001
            pass
    return {"items": out, "seeds": len(seeds)}


@app.post("/lib/import")
def lib_import(profile: str = Depends(prof)):
    """Impor playlist dari akun YouTube (butuh login aktif). Hasilnya jadi playlist lokal."""
    if not (catalog.authed() and catalog.session_alive()):
        raise HTTPException(403, "Perlu login YouTube yang masih aktif untuk impor. "
                                 "Semua fitur lokal tetap jalan tanpa login.")
    created = []
    for src in call(catalog.library):
        if not str(src.get("id", "")).startswith(("PL", "LM", "VL")):
            continue
        try:
            d = catalog.detail("playlist", src["id"])
        except Exception:  # noqa: BLE001
            continue
        pl = lib.playlist_create(profile, src.get("title") or "Impor")
        n = 0
        for t in d.get("tracks") or []:
            if lib.playlist_add(profile, pl["id"], {"videoId": t["id"], "title": t.get("title"),
                                                    "subtitle": t.get("subtitle"),
                                                    "thumb": t.get("thumb"), "dur": t.get("dur")}):
                n += 1
        created.append({"name": pl["title"], "tracks": n})
    return {"ok": True, "playlists": created}


async def _json(request: Request):
    try:
        raw = await request.body()
        if not raw:
            return {}
        import json as _json_mod
        return _json_mod.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "body JSON tidak valid")
