"""Pembungkus ytmusicapi: hasil dinormalisasi jadi format sederhana untuk aplikasi.

Klien dibuat malas (lazy): YTMusic() melakukan panggilan jaringan saat dibuat, jadi kalau
dibuat saat import maka service bisa telat start atau crash-loop ketika jaringan belum siap.
"""
import os
import threading

from ytmusicapi import YTMusic

from . import config

_lock = threading.Lock()
_client = None
_authed = None


def authed():
    """True bila browser.json tersedia (tab Pustaka butuh login)."""
    global _authed
    if _authed is None:
        _authed = os.path.exists(config.AUTH_FILE)
    return _authed


def _oauth_mode() -> bool:
    """True kalau file auth berisi token OAuth (bukan cookie browser)."""
    if not (config.OAUTH_ID and config.OAUTH_SECRET):
        return False
    try:
        import json as _json
        with open(config.AUTH_FILE, encoding="utf-8") as f:
            return "refresh_token" in _json.load(f)
    except Exception:  # noqa: BLE001
        return False


def yt():
    """Klien YTMusic tunggal, dibuat saat pertama dibutuhkan."""
    global _client
    if _client is None:
        with _lock:
            if _client is None:
                if _oauth_mode():
                    from ytmusicapi.auth.oauth import OAuthCredentials
                    creds = OAuthCredentials(config.OAUTH_ID, config.OAUTH_SECRET)
                    _client = YTMusic(config.AUTH_FILE, oauth_credentials=creds)
                elif authed():
                    _client = YTMusic(config.AUTH_FILE)
                else:
                    _client = YTMusic()
    return _client


def reset():
    """Paksa klien dibuat ulang (dipakai setelah browser.json dipasang)."""
    global _client, _authed
    with _lock:
        _client = None
        _authed = None


def _thumb(x):
    t = x.get("thumbnails") or x.get("thumbnail") or []
    return t[-1]["url"] if t else ""


def _names(v):
    if isinstance(v, list):
        return ", ".join(i.get("name", "") for i in v if isinstance(i, dict) and i.get("name"))
    if isinstance(v, dict):
        return v.get("name", "")
    if isinstance(v, str):
        return v
    return ""


def norm(x, fallback_thumb=""):
    if not isinstance(x, dict):
        return None
    rt = x.get("resultType")
    thumb = _thumb(x) or fallback_thumb
    title = x.get("title") or x.get("artist") or x.get("name") or ""
    vid = x.get("videoId")
    who = _names(x.get("artists")) or _names(x.get("author")) or (
        x.get("artist") if isinstance(x.get("artist"), str) and x.get("title") else "")

    if vid:
        return {"kind": "song", "id": vid, "title": title, "subtitle": who,
                "thumb": thumb, "dur": int(x.get("duration_seconds") or 0)}

    bid = x.get("browseId") or ""
    pid = x.get("playlistId") or ""
    if rt == "artist" or bid.startswith("UC"):
        kind, iid, sub = "artist", bid, "Artis"
    elif rt == "album" or bid.startswith("MPRE"):
        year = x.get("year")
        kind, iid = "album", bid
        sub = " • ".join(p for p in ["Album", who, str(year) if year else ""] if p)
    else:
        kind = "playlist"
        iid = pid or (bid[2:] if bid.startswith("VL") else bid)
        sub = " • ".join(p for p in ["Playlist", who] if p)
    if not iid:
        return None
    return {"kind": kind, "id": iid, "title": title, "subtitle": sub, "thumb": thumb, "dur": 0}


def _clean(items, fallback=""):
    return [i for i in (norm(x, fallback) for x in items) if i]


def search(q, kind):
    flt = kind if kind in ("songs", "albums", "artists", "playlists") else "songs"
    return _clean(yt().search(q, filter=flt, limit=25))


def home():
    out = []
    for s in yt().get_home(limit=6):
        items = _clean(s.get("contents") or [])
        if items:
            out.append({"title": s.get("title", ""), "items": items[:20]})
    return out


def library():
    items = _clean(yt().get_library_playlists(limit=50))
    liked = {"kind": "playlist", "id": "LM", "title": "Lagu yang disukai",
             "subtitle": "Playlist", "thumb": "", "dur": 0}
    return [liked] + items


def _artist_tracks(d):
    """get_artist hanya memberi 5 lagu teratas; ambil daftar penuh lewat browseId-nya."""
    songs = d.get("songs") or {}
    tracks = songs.get("results") or []
    bid = songs.get("browseId")
    if bid:
        try:
            pl = yt().get_playlist(bid, limit=100)
            full = pl.get("tracks") or []
            if len(full) > len(tracks):
                return full
        except Exception:  # noqa: BLE001
            pass
    return tracks


def session_alive():
    """Cek apakah cookie di browser.json masih sesi aktif (Google sering membatalkannya)."""
    try:
        import json as _json
        import httpx
        h = _json.load(open(config.AUTH_FILE, encoding="utf-8"))
        r = httpx.get("https://music.youtube.com/", timeout=20, follow_redirects=True,
                      headers={"Cookie": h.get("cookie", ""),
                               "User-Agent": h.get("user-agent", "Mozilla/5.0"),
                               "Accept-Language": "en-US,en;q=0.9"})
        return '"LOGGED_IN": true' in r.text
    except Exception:  # noqa: BLE001
        return False


def detail(kind, iid):
    if kind == "playlist" and iid in ("LM", "VLLM"):
        # "Lagu yang disukai" bukan playlist biasa - harus lewat get_liked_songs.
        d = yt().get_liked_songs(limit=300)
        tracks = d.get("tracks") or []
        d["title"] = "Lagu yang disukai"
        sub = "Playlist"
    elif kind == "album":
        d = yt().get_album(iid)
        tracks = d.get("tracks") or []
        year = d.get("year")
        sub = " • ".join(p for p in ["Album", _names(d.get("artists")), str(year) if year else ""] if p)
    elif kind == "artist":
        d = yt().get_artist(iid)
        tracks = _artist_tracks(d)
        d["title"] = d.get("name")
        sub = "Artis"
    else:
        d = yt().get_playlist(iid, limit=200)
        tracks = d.get("tracks") or []
        sub = " • ".join(p for p in ["Playlist", _names(d.get("author"))] if p)
    thumb = _thumb(d)
    songs = [i for i in _clean(tracks, thumb) if i["kind"] == "song"]
    return {"kind": kind, "id": iid, "title": d.get("title") or "", "subtitle": sub,
            "thumb": thumb, "tracks": songs}


def radio(vid):
    w = yt().get_watch_playlist(videoId=vid, limit=25, radio=True)
    return [i for i in _clean(w.get("tracks") or []) if i["kind"] == "song" and i["id"] != vid]


def lyrics(vid):
    w = yt().get_watch_playlist(videoId=vid)
    lid = w.get("lyrics")
    if not lid:
        return ""
    l = yt().get_lyrics(lid)
    return (l.get("lyrics") if isinstance(l, dict) else getattr(l, "lyrics", "")) or ""