import hashlib
import io
import os
import re
import secrets
import threading
from urllib.parse import urlparse

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, Response, StreamingResponse
from PIL import Image, ImageOps
from starlette.background import BackgroundTask

from . import catalog, config, resolver

if not config.TOKEN:
    raise RuntimeError("Environment variable YTM_TOKEN belum diisi")


def auth(x_token: str = Header(None), t: str = Query(None)):
    given = (x_token or t or "").encode()
    if not secrets.compare_digest(given, config.TOKEN.encode()):
        raise HTTPException(401, "Token salah")


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