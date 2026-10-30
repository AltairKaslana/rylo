"""Pembungkus ytmusicapi: hasil dinormalisasi jadi format sederhana untuk aplikasi.

Klien dibuat malas (lazy): YTMusic() melakukan panggilan jaringan saat dibuat, jadi kalau
dibuat saat import maka service bisa telat start atau crash-loop ketika jaringan belum siap.

Sejak mode publik, kredensial Google disimpan PER AKUN: <AUTH_DIR>/<profile>.json.
Pemanggil wajib menyebut akunnya (`who`, nilai = profile) supaya beranda personal dan
Pustaka memakai kredensial pemilik akun itu, bukan satu kredensial bersama.
`who=None` berarti klien anonim: cari, beranda publik, radio, lirik, dan putar tetap jalan,
tapi endpoint yang butuh login menolak dengan jelas (lihat authed()).
"""
import os
import threading

from ytmusicapi import YTMusic

from . import config

_guard = threading.Lock()
_clients = {}
_locks = {}


def _lock_for(key):
    with _guard:
        return _locks.setdefault(key, threading.Lock())


def auth_path(who):
    """Berkas kredensial untuk satu akun, atau None kalau akun itu belum menautkan Google."""
    if not who:
        return None
    p = os.path.join(config.AUTH_DIR, str(who) + ".json")
    return p if os.path.exists(p) else None


def authed(who):
    """True kalau akun ini sudah menautkan Google (browser.json versi per-akun)."""
    return bool(auth_path(who))


def _oauth_mode(path: str) -> bool:
    """True kalau berkas auth berisi token OAuth (bukan cookie browser)."""
    if not (config.OAUTH_ID and config.OAUTH_SECRET):
        return False
    try:
        import json as _json
        with open(path, encoding="utf-8") as f:
            return "refresh_token" in _json.load(f)
    except Exception:  # noqa: BLE001
        return False


def yt(who=None):
    """Klien YTMusic untuk satu akun (atau anonim), dibuat saat pertama dibutuhkan."""
    key = str(who) if who else "anon"
    client = _clients.get(key)
    if client is not None:
        return client
    with _lock_for(key):
        client = _clients.get(key)
        if client is not None:
            return client
        path = auth_path(who)
        if path and _oauth_mode(path):
            from ytmusicapi.auth.oauth import OAuthCredentials
            creds = OAuthCredentials(config.OAUTH_ID, config.OAUTH_SECRET)
            client = YTMusic(path, oauth_credentials=creds)
        elif path:
            client = YTMusic(path)
        else:
            client = YTMusic()
        _clients[key] = client
        return client


def reset(who=None):
    """Paksa klien dibuat ulang (dipakai setelah kredensial akun dipasang atau dicabut)."""
    if who is None:
        with _guard:
            _clients.clear()
        return
    key = str(who)
    with _lock_for(key):
        _clients.pop(key, None)


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


def search(q, kind, who=None):
    flt = kind if kind in ("songs", "albums", "artists", "playlists") else "songs"
    return _clean(yt(who).search(q, filter=flt, limit=25))


def home(who=None):
    out = []
    for s in yt(who).get_home(limit=6):
        items = _clean(s.get("contents") or [])
        if items:
            out.append({"title": s.get("title", ""), "items": items[:20]})
    return out


def library(who=None):
    items = _clean(yt(who).get_library_playlists(limit=50))
    liked = {"kind": "playlist", "id": "LM", "title": "Lagu yang disukai",
             "subtitle": "Playlist", "thumb": "", "dur": 0}
    return [liked] + items


def _artist_tracks(d, who=None):
    """get_artist hanya memberi 5 lagu teratas; ambil daftar penuh lewat browseId-nya."""
    songs = d.get("songs") or {}
    tracks = songs.get("results") or []
    bid = songs.get("browseId")
    if bid:
        try:
            pl = yt(who).get_playlist(bid, limit=100)
            full = pl.get("tracks") or []
            if len(full) > len(tracks):
                return full
        except Exception:  # noqa: BLE001
            pass
    return tracks


def session_alive(who=None):
    """Cek apakah kredensial akun ini masih hidup (cookie bisa dibatalkan Google)."""
    path = auth_path(who)
    if not path:
        return False
    if _oauth_mode(path):
        try:
            yt(who).get_library_playlists(limit=1)
            return True
        except Exception:  # noqa: BLE001
            return False
    try:
        import json as _json
        import httpx
        h = _json.load(open(path, encoding="utf-8"))
        r = httpx.get("https://music.youtube.com/", timeout=20, follow_redirects=True,
                      headers={"Cookie": h.get("cookie", ""),
                               "User-Agent": h.get("user-agent", "Mozilla/5.0"),
                               "Accept-Language": "en-US,en;q=0.9"})
        return '"LOGGED_IN": true' in r.text
    except Exception:  # noqa: BLE001
        return False


def detail(kind, iid, who=None):
    if kind == "playlist" and iid in ("LM", "VLLM"):
        # "Lagu yang disukai" bukan playlist biasa - harus lewat get_liked_songs.
        d = yt(who).get_liked_songs(limit=300)
        tracks = d.get("tracks") or []
        d["title"] = "Lagu yang disukai"
        sub = "Playlist"
    elif kind == "album":
        d = yt(who).get_album(iid)
        tracks = d.get("tracks") or []
        year = d.get("year")
        sub = " • ".join(p for p in ["Album", _names(d.get("artists")), str(year) if year else ""] if p)
    elif kind == "artist":
        d = yt(who).get_artist(iid)
        tracks = _artist_tracks(d, who)
        d["title"] = d.get("name")
        sub = "Artis"
    else:
        d = yt(who).get_playlist(iid, limit=200)
        tracks = d.get("tracks") or []
        sub = " • ".join(p for p in ["Playlist", _names(d.get("author"))] if p)
    thumb = _thumb(d)
    songs = [i for i in _clean(tracks, thumb) if i["kind"] == "song"]
    return {"kind": kind, "id": iid, "title": d.get("title") or "", "subtitle": sub,
            "thumb": thumb, "tracks": songs}


def suggest(q, who=None):
    """Saran pencarian saat pengguna mengetik."""
    try:
        return list(yt(who).get_search_suggestions(q))[:10]
    except Exception:  # noqa: BLE001
        return []


def radio(vid, who=None):
    w = yt(who).get_watch_playlist(videoId=vid, limit=25, radio=True)
    return [i for i in _clean(w.get("tracks") or []) if i["kind"] == "song" and i["id"] != vid]


def lyrics(vid, who=None):
    w = yt(who).get_watch_playlist(videoId=vid)
    lid = w.get("lyrics")
    if not lid:
        return ""
    l = yt(who).get_lyrics(lid)
    return (l.get("lyrics") if isinstance(l, dict) else getattr(l, "lyrics", "")) or ""
