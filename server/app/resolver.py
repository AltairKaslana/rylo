"""Mengambil URL audio langsung via yt-dlp dan menyimpannya sementara di memori."""
import threading
import time

import yt_dlp

from . import config

_cache = {}
_locks = {}
_guard = threading.Lock()


def _extract(vid):
    opts = {
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
    }
    if config.COOKIES:
        opts["cookiefile"] = config.COOKIES
    with yt_dlp.YoutubeDL(opts) as y:
        info = y.extract_info(f"https://music.youtube.com/watch?v={vid}", download=False)
    return {
        "url": info["url"],
        "headers": info.get("http_headers", {}),
        "exp": time.time() + config.URL_TTL,
    }


def _prune():
    now = time.time()
    if len(_cache) > 200:
        for k in [k for k, v in _cache.items() if v["exp"] < now]:
            _cache.pop(k, None)
            _locks.pop(k, None)


def get(vid, force=False):
    with _guard:
        lock = _locks.setdefault(vid, threading.Lock())
    with lock:
        hit = _cache.get(vid)
        if hit and not force and hit["exp"] > time.time():
            return hit
        res = _extract(vid)
        _cache[vid] = res
        _prune()
        return res


def cached(vid):
    """True kalau URL audio untuk vid sudah ada di cache dan belum kedaluwarsa."""
    hit = _cache.get(vid)
    return bool(hit and hit["exp"] > time.time())
