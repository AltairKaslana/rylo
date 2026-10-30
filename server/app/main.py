import hashlib
import io
import os
import re
import secrets
import shutil
import concurrent.futures
import time
from urllib.parse import urlparse

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, HTMLResponse, Response, StreamingResponse
from PIL import Image, ImageOps
from starlette.background import BackgroundTask

from . import accounts as acc
from . import catalog, config, resolver
from . import library as lib

lib.init()
acc.init()          # tabel akun/sesi, idempoten

if not config.TOKEN:
    raise RuntimeError("Environment variable YTM_TOKEN belum diisi")


def _migrate_legacy_auth():
    """Kredensial lama (satu berkas untuk semua orang) -> AUTH_DIR/owner.json.

    Berkas lama TIDAK dihapus: ia jadi sumber yang bisa diadopsi lewat
    /auth/google/adopt, jadi kalau migrasi keliru aslinya masih ada.
    """
    try:
        src = config.AUTH_FILE if os.path.isabs(config.AUTH_FILE) \
            else os.path.join(os.getcwd(), config.AUTH_FILE)
        if not os.path.exists(src):
            return
        os.makedirs(config.AUTH_DIR, exist_ok=True)
        dst = os.path.join(config.AUTH_DIR, "owner.json")
        if os.path.exists(dst):
            return
        shutil.copyfile(src, dst)
        os.chmod(dst, 0o600)
        print(f"[ytm] kredensial lama disalin ke {dst} (berkas asli tetap ada)", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"[ytm] migrasi kredensial lama dilewati: {e}", flush=True)


_migrate_legacy_auth()


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


# Awalan path yang TIDAK digerbang kunci master, karena memvalidasi diri sendiri
# lewat token sesi:
#   /auth/login, /auth/me, /auth/logout, /auth/alias  -> cek sesi di dalam endpoint
#   /lib/* dan /profile                              -> WAJIB sesi, header X-Profile
#      tidak dipercaya lagi. Kalau /auth/login ikut digerbang kunci master, siapa pun
#      yang tahu PIN bisa masuk sebagai profil siapa pun dan password profil jadi
#      tidak ada artinya. Kalau /lib/* masih menerima header bebas, lubang lamanya
#      (ganti header = baca data profil lain) tetap terbuka.
def _needs_session(path: str) -> bool:
    return (path.startswith("/lib/") or path == "/profile"
            or path in ("/auth/login", "/auth/me", "/auth/logout", "/auth/alias",
                        "/auth/register", "/auth/invite", "/auth/invites", "/auth/google",
                        "/setup"))


def auth(request: Request, x_token: str = Header(None), t: str = Query(None),
         x_session: str = Header(None)):
    """Terima token master, PIN pendek, ATAU token sesi akun biasa (mode publik).

    Kalau pemanggil masuk sebagai akun biasa, request.state.who diisi profile-nya;
    endpoint katalog lalu memakai kredensial Google milik akun itu. Tanpa sesi,
    endpoint memakai klien anonim (tetap boleh mencari, radio, putar).
    """
    ip = (request.client.host if request.client else "?") or "?"
    if _needs_session(request.url.path):
        return                      # diperiksa di dalam endpoint masing-masing
    if not _rate_check(ip):
        raise HTTPException(429, "Terlalu banyak percobaan, coba lagi 10 menit")
    given = x_token or t or ""
    if _same(given, config.TOKEN) or _same(given, config.PIN):
        _FAILS.pop(ip, None)
        request.state.master = True
        return
    if x_session:
        me = acc.me(x_session)
        if me:
            _FAILS.pop(ip, None)
            request.state.who = me["profile"]
            request.state.acct = me
            return
    _rate_fail(ip)
    raise HTTPException(401, "Token/PIN/sesi tidak valid")


def _who(request: Request):
    """Profile pemanggil (kalau login sebagai akun), atau None untuk anonim."""
    return getattr(request.state, "who", None)


app = FastAPI(dependencies=[Depends(auth)], docs_url=None, redoc_url=None, openapi_url=None)
VID = re.compile(r"^[\w-]{11}$")
IMG_HOSTS = ("googleusercontent.com", "ggpht.com", "ytimg.com")
os.makedirs(config.IMG_CACHE, exist_ok=True)

# Preload URL audio di belakang supaya "tekan play -> bunyi" tidak menunggu ~3,5s
# ekstraksi yt-dlp. Pool terbatas: jangan banjiri YouTube/CPU box yang lemah.
_prefetch_pool = concurrent.futures.ThreadPoolExecutor(
    max_workers=max(1, config.PREFETCH_WORKERS), thread_name_prefix="prefetch")


def _prefetch_one(vid):
    try:
        resolver.get(vid)
    except Exception:  # noqa: BLE001
        pass            # prefetch gagal tidak mengganggu; play nanti resolve sendiri


def schedule_prefetch(vids):
    """Anakan ekstraksi URL audio untuk daftar video yang belum ter-cache."""
    for v in vids:
        if not (v and VID.match(v)) or resolver.cached(v):
            continue
        try:
            _prefetch_pool.submit(_prefetch_one, v)
        except RuntimeError:
            pass        # pool sudah dimatikan (saat shutdown)


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


# ---------- kuota per akun (mode publik) ----------
# Box ini 1,9 GB RAM: kuota bukan untuk menekan pengguna, tapi supaya satu orang
# yang memutar terus-menerus tidak membuat layanan mati untuk orang lain.

_SEARCH_HITS = {}
_STREAMS = {}
_STREAM_LOCK = __import__("threading").Lock()


def _search_quota(who):
    if not (config.PUBLIC and who):
        return
    now = time.time()
    hits = [t for t in _SEARCH_HITS.get(who, []) if now - t < config.SEARCH_WINDOW]
    if len(hits) >= config.SEARCH_LIMIT:
        raise HTTPException(429, "Terlalu banyak pencarian, coba lagi beberapa menit")
    hits.append(now)
    _SEARCH_HITS[who] = hits


def _stream_enter(who):
    """Pesan satu slot stream. Kembalikan kunci untuk dilepas, atau None kalau anonim."""
    if not (config.PUBLIC and who):
        return None
    with _STREAM_LOCK:
        n = _STREAMS.get(who, 0)
        if n >= config.MAX_STREAMS:
            raise HTTPException(429, f"Terlalu banyak lagu diputar bersamaan (maks {config.MAX_STREAMS})")
        _STREAMS[who] = n + 1
    return who


def _stream_leave(key):
    if not key:
        return
    with _STREAM_LOCK:
        _STREAMS[key] = max(0, _STREAMS.get(key, 1) - 1)


@app.get("/health")
def health(request: Request):
    return {"ok": True, "authed": catalog.authed(_who(request))}


@app.get("/home")
def home(request: Request):
    who = _who(request)
    return {"sections": call(catalog.home, who)}


@app.get("/search")
def search(q: str, type: str = "songs", request: Request = None):
    who = _who(request)
    _search_quota(who)
    items = call(catalog.search, q, type, who)
    if type == "songs":
        schedule_prefetch([it.get("id") for it in items[:config.PREFETCH]])
    return {"items": items}


@app.get("/auth-status")
def auth_status(request: Request):
    """Status tautan Google milik akun pemanggil (bukan akun bersama)."""
    who = _who(request)
    ok = catalog.authed(who)
    return {"authed": ok, "logged_in": catalog.session_alive(who) if ok else False}


@app.get("/suggest")
def suggest(q: str, request: Request = None):
    if len(q.strip()) < 2:
        return {"items": []}
    who = _who(request)
    return {"items": call(catalog.suggest, q, who)}


@app.get("/library")
def library(request: Request):
    who = _who(request)
    if not catalog.authed(who):
        raise HTTPException(403, "Akun ini belum menautkan Google - "
                                 "buka menu Akun lalu pilih Hubungkan YouTube Music")
    return {"items": call(catalog.library, who)}


@app.get("/detail/{kind}/{iid}")
def detail(kind: str, iid: str, request: Request = None):
    if kind not in ("album", "artist", "playlist"):
        raise HTTPException(400, "kind tidak valid")
    who = _who(request)
    return call(catalog.detail, kind, iid, who)


@app.get("/radio/{vid}")
def radio(vid: str, request: Request = None):
    check_vid(vid)
    who = _who(request)
    items = call(catalog.radio, vid, who)
    schedule_prefetch([it.get("id") for it in items[:config.PREFETCH]])
    return {"items": items}


@app.get("/lyrics/{vid}")
def lyrics(vid: str, request: Request):
    check_vid(vid)
    who = _who(request)
    return {"lyrics": call(catalog.lyrics, vid, who) or ""}


@app.get("/prefetch/{vid}")
def prefetch(vid: str):
    check_vid(vid)
    schedule_prefetch([vid])
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
    slot = _stream_enter(_who(request))
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
        _stream_leave(slot)

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
<h1>Hubungkan YouTube Music</h1>
<p class="muted">Sekali saja, khusus akun yang sedang masuk. Cookie disimpan
<strong>hanya untuk akun ini</strong> - pengguna lain memakai akun Google-nya sendiri
dan tidak bisa melihat data Anda.</p>
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
(berkas tersendiri untuk akun Anda, izin 600) dan tidak pernah dikirim ke tempat lain.
Pakai akun khusus bila ingin memisahkan risiko.</p>
<script>
async function save(){
  const t=document.getElementById('h').value.trim();
  const r=document.getElementById('r');
  if(!t){ r.className='bad'; r.textContent='Masih kosong.'; return; }
  r.className='muted'; r.textContent='Menyimpan...';
  const qs=new URLSearchParams(location.search);
  const h={};
  if(qs.get('s')) h['X-Session']=qs.get('s');
  try{
    const res=await fetch('/setup'+(qs.get('t')?'?t='+encodeURIComponent(qs.get('t')):''),{method:'POST',headers:{'Content-Type':'text/plain',...h},body:t});
    const j=await res.json();
    if(res.ok){ r.className='ok'; r.textContent='Berhasil. authed='+j.authed+' - buka tab Pustaka di aplikasi.'; }
    else { r.className='bad'; r.textContent='Gagal: '+(j.detail||res.status); }
  }catch(e){ r.className='bad'; r.textContent='Gagal menghubungi server.'; }
}
</script></body></html>"""


def _auth_target(request: Request, x_session: str = Header(None),
                 x_token: str = Header(None), t: str = Query(None)):
    """Tentukan berkas kredensial untuk request /setup.

    Jalur biasa: token sesi -> berkas milik akun itu. Jalur lama (token master)
    tetap diterima supaya Tuan bisa menyiapkan kredensial dari PC; hasilnya masuk
    ke berkas "owner.json" yang bisa diadopsi akun mana pun lewat /auth/google/adopt.
    """
    me = acc.me(x_session) if x_session else None
    if me:
        return me["profile"]
    given = x_token or t or ""
    if _same(given, config.TOKEN) or _same(given, config.PIN):
        return "owner"
    raise HTTPException(401, "Perlu login: buka halaman ini dari aplikasi setelah masuk")


@app.get("/setup", response_class=HTMLResponse)
def setup_page(s: str = Query(None), t: str = Query(None)):
    q = f"?s={s}" if s else (f"?t={t}" if t else "")
    return SETUP_HTML.replace("/setup'", "/setup" + q + "'")


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
async def setup_save(request: Request, x_session: str = Header(None),
                     x_token: str = Header(None), t: str = Query(None)):
    """Simpan header YouTube milik satu akun (tidak lewat chat, tidak lewat log)."""
    who = _auth_target(request, x_session, x_token, t)
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

    os.makedirs(config.AUTH_DIR, exist_ok=True)
    target = os.path.join(config.AUTH_DIR, str(who) + ".json")
    try:
        from ytmusicapi import setup as ytm_setup
        ytm_setup(filepath=target, headers_raw=raw)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, f"Header tidak valid: {type(e).__name__}: {str(e)[:200]}")
    try:
        os.chmod(target, 0o600)
    except OSError:
        pass
    catalog.reset(who)
    return {"ok": True, "authed": catalog.authed(who)}


# ---------- tautan Google per akun ----------

@app.get("/auth/google")
def google_status(x_session: str = Header(None)):
    """Status tautan YouTube Music milik akun yang sedang masuk."""
    me = acc.me(x_session)
    if not me:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    who = me["profile"]
    linked = catalog.authed(who)
    return {"linked": linked,
            "logged_in": catalog.session_alive(who) if linked else False}


@app.delete("/auth/google")
def google_unlink(x_session: str = Header(None)):
    """Cabut tautan: hapus berkas kredensial akun ini. Data lokal tidak tersentuh."""
    me = acc.me(x_session)
    if not me:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    path = catalog.auth_path(me["profile"])
    if path and os.path.exists(path):
        try:
            os.remove(path)
        except OSError as e:
            raise HTTPException(500, f"Gagal menghapus berkas kredensial: {e}")
    catalog.reset(me["profile"])
    return {"ok": True, "linked": False}


@app.post("/auth/google/adopt")
async def google_adopt(x_session: str = Header(None)):
    """Adopsi kredensial yang disiapkan lewat token master (owner.json) untuk akun ini."""
    me = acc.me(x_session)
    if not me:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    src = os.path.join(config.AUTH_DIR, "owner.json")
    if not os.path.exists(src):
        raise HTTPException(404, "Tidak ada kredensial owner.json yang bisa diadopsi")
    dst = catalog.auth_path(me["profile"])
    if dst and os.path.exists(dst):
        raise HTTPException(400, "Akun ini sudah punya tautan sendiri")
    os.makedirs(config.AUTH_DIR, exist_ok=True)
    import shutil
    shutil.copyfile(src, dst)
    os.chmod(dst, 0o600)
    catalog.reset(me["profile"])
    return {"ok": True, "linked": True}


# ---------- kode undangan (mode publik) ----------

@app.post("/auth/invite")
async def invite_create(request: Request, x_token: str = Header(None), t: str = Query(None)):
    """Buat kode undangan. Hanya kunci master. Kode mentah hanya muncul sekali."""
    if not _master_ok(x_token or t or ""):
        raise HTTPException(403, "Kunci master diperlukan untuk membuat kode undangan")
    body = await _json(request)
    try:
        return acc.create_invite(True, days=body.get("days"), note=body.get("note"))
    except PermissionError as e:
        raise HTTPException(403, str(e))


@app.get("/auth/invites")
def invite_list(x_token: str = Header(None), t: str = Query(None)):
    """Daftar kode undangan: mana yang masih kosong, sudah dipakai siapa."""
    if not _master_ok(x_token or t or ""):
        raise HTTPException(403, "Kunci master diperlukan")
    try:
        return {"items": acc.list_invites(True)}
    except PermissionError as e:
        raise HTTPException(403, str(e))

# ---------------- koleksi lokal (tanpa akun YouTube) ----------------

def _master_ok(given: str) -> bool:
    """Kunci master = PIN/token server. Dipakai untuk operasi admin."""
    return bool(given) and (_same(given, config.TOKEN) or _same(given, config.PIN))


def _auth_body(body: dict):
    """Ambil username/sandi/alias dari body dengan batas panjang yang wajar."""
    return (str(body.get("username") or "").strip()[:64],
            str(body.get("password") or "")[:128],
            str(body.get("alias") or "").strip()[:24])


def prof(x_session: str = Header(None), x_profile: str = Header(None)) -> str:
    """
    Profil yang dipakai request ini - SELALU dari token sesi.

    Header X-Profile sengaja tidak dipercaya lagi. Sebelumnya header itu dipercaya
    apa adanya, sehingga siapa pun yang tahu PIN server bisa membaca dan mengubah
    data profil mana pun hanya dengan mengganti nilai header. Sekarang tanpa sesi
    yang sah request ditolak, jadi data favorit/playlist/riwayat hanya bisa disentuh
    oleh pemegang akunnya sendiri.

    x_profile masih diterima sebagai parameter supaya tanda tangan fungsi tidak
    berubah, tetapi nilainya diabaikan.
    """
    who = acc.me(x_session) if x_session else None
    if not who:
        raise HTTPException(401, "Perlu login: token sesi tidak valid atau sudah kedaluwarsa")
    return who["profile"]


@app.get("/auth/me")
def auth_me(x_session: str = Header(None)):
    who = acc.me(x_session)
    if not who:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    linked = catalog.authed(who["profile"])
    out = dict(who)
    out["google"] = {"linked": linked,
                     "logged_in": catalog.session_alive(who["profile"]) if linked else False}
    return out


@app.post("/auth/register")
async def auth_register(request: Request, x_token: str = Header(None), t: str = Query(None)):
    """Daftar akun baru.

    Mode publik: wajib kode undangan (dibuat Tuan lewat /auth/invite). Tanpa mode
    publik, tetap butuh kunci master - supaya alamat server tidak bisa dipakai
    orang lain membuat akun seenaknya.
    """
    body = await _json(request)
    u, p, a = _auth_body(body)
    ip = (request.client.host if request.client else "") or ""
    master = _master_ok(x_token or t or "")
    code = str(body.get("code") or "").strip()
    try:
        if master:
            info = acc.register(u, p, a, True, ip=ip)
        elif config.PUBLIC:
            if not code:
                raise HTTPException(403, "Pendaftaran memakai kode undangan dari Tuan")
            if acc.count_signups_since(ip, int(time.time()) - 3600) >= config.SIGNUP_PER_HOUR:
                raise HTTPException(429, "Terlalu banyak pendaftaran dari jaringan ini, coba lagi nanti")
            info = acc.redeem_invite(code, u, p, a)
        else:
            raise HTTPException(403, "Kunci master diperlukan untuk membuat akun")
        return acc.login(u, p) or info      # langsung masuk setelah daftar
    except HTTPException:
        raise
    except PermissionError as e:
        raise HTTPException(403, str(e))
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.post("/auth/login")
async def auth_login(request: Request):
    body = await _json(request)
    u, p, _ = _auth_body(body)
    # Kunci per-IP-per-username, BUKAN per-IP polos: di belakang bore semua klien
    # tampak 127.0.0.1 (pitfall 13), jadi kunci IP polos akan mengunci SEMUA pengguna
    # begitu satu orang salah sandi. Dengan kunci ini hanya akun yang ditebak yang
    # terbebani; pengguna lain tetap bisa masuk. Pola sama dengan auth() di atas.
    ip = (request.client.host if request.client else "?") or "?"
    key = f"login:{ip}:{u}"
    if not _rate_check(key):
        raise HTTPException(429, "Terlalu banyak percobaan gagal, coba lagi 10 menit")
    ses = acc.login(u, p)
    if not ses:
        _rate_fail(key)          # salah sandi = percobaan gagal, ikut dihitung
        raise HTTPException(401, "Username atau sandi salah")
    _FAILS.pop(key, None)        # berhasil -> bersihkan hitungan akun ini
    return ses


@app.post("/auth/logout")
async def auth_logout(x_session: str = Header(None)):
    acc.logout(x_session)
    return {"ok": True}


@app.post("/auth/alias")
async def auth_alias(request: Request, x_session: str = Header(None)):
    who = acc.me(x_session)
    if not who:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    body = await _json(request)
    return {"alias": acc.set_alias(who["username"], body.get("alias"))}


@app.post("/auth/adopt")
async def auth_adopt(request: Request, x_session: str = Header(None),
                     x_token: str = Header(None), t: str = Query(None)):
    """Pindahkan data profil lama (mis. hp-dce769) ke akun yang sedang masuk."""
    who = acc.me(x_session)
    if not who:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    if not _master_ok(x_token or t or ""):
        raise HTTPException(403, "Kunci master diperlukan untuk mengadopsi profil")
    body = await _json(request)
    try:
        return acc.adopt(body.get("old_profile"), who["username"], True)
    except ValueError as e:
        raise HTTPException(400, str(e))


@app.get("/profile")
def profile_info(profile: str = Depends(prof)):
    return lib.touch_profile(profile)


@app.post("/profile")
async def profile_rename(request: Request, profile: str = Depends(prof),
                         x_session: str = Header(None)):
    """Ganti alias. Nama profil lama (lib.touch_profile) tidak lagi dipakai."""
    body = await _json(request)
    name = (body.get("name") or "").strip()[:40] or None
    who = acc.me(x_session)          # prof() sudah memastikan sesi sah
    if not who:
        raise HTTPException(401, "Sesi tidak valid atau sudah kedaluwarsa")
    return {"alias": acc.set_alias(who["username"], name)}


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
async def remember_search(request: Request, profile: str = Depends(prof)):
    body = await _json(request)
    q = (body.get("q") or "").strip()
    if not q:
        raise HTTPException(400, "q wajib")
    # lagu yang dipilih pengguna (opsional) - supaya bisa langsung diputar lagi
    item = {k: body.get(k) for k in ("video_id", "title", "subtitle", "thumb", "dur")}
    lib.search_add(profile, q, body.get("kind") or "songs", item)
    return {"ok": True}


@app.get("/lib/recent-songs")
def recent_songs_route(profile: str = Depends(prof), limit: int = 30):
    """Lagu yang pernah dipilih dari pencarian, rata & terbaru lebih dulu.

    Tab Pencarian memakai ini supaya lagunya tampil langsung beserta thumbnail
    dan bisa diputar tanpa membuka daftar pencarian dulu.
    """
    return {"items": lib.recent_songs(profile, limit)}


@app.delete("/lib/recent-searches")
async def forget_search(request: Request, profile: str = Depends(prof), q: str = "", vid: str = ""):
    """Hapus satu lagu (q+vid), satu pencarian (q), atau semua (tanpa q)."""
    if q.strip() and vid.strip():
        lib.search_item_remove(profile, q, vid)
    elif q.strip():
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
            for it in catalog.radio(vid, profile):
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
            for sec in catalog.home(profile):
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
    """Impor playlist dari akun YouTube milik pemanggil (butuh tautan Google). Hasilnya playlist lokal."""
    if not (catalog.authed(profile) and catalog.session_alive(profile)):
        raise HTTPException(403, "Perlu menautkan akun YouTube yang masih aktif untuk impor. "
                                 "Semua fitur lokal tetap jalan tanpa tautan.")
    created = []
    for src in call(catalog.library, profile):
        if not str(src.get("id", "")).startswith(("PL", "LM", "VL")):
            continue
        try:
            d = catalog.detail("playlist", src["id"], profile)
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
