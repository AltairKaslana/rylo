"""library.py - koleksi milik pengguna: favorit, riwayat, playlist.

Disimpan di SQLite di server supaya tidak bergantung pada akun YouTube (yang sesinya
sering dibatalkan Google) dan tidak hilang saat aplikasi dipasang ulang.
"""
import os
import sqlite3
import time

DB_PATH = os.getenv("YTM_LIBRARY", "/opt/ytm-relay/library.db")


def _conn():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init():
    with _conn() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS tracks (
            video_id TEXT PRIMARY KEY,
            title    TEXT,
            subtitle TEXT,
            thumb    TEXT,
            dur      INTEGER DEFAULT 0,
            seen_at  INTEGER
        );
        CREATE TABLE IF NOT EXISTS favorites (
            video_id TEXT PRIMARY KEY,
            added_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS playlists (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            name       TEXT NOT NULL,
            created_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS playlist_items (
            playlist_id INTEGER NOT NULL,
            video_id    TEXT NOT NULL,
            position    INTEGER DEFAULT 0,
            added_at    INTEGER,
            PRIMARY KEY (playlist_id, video_id)
        );
        CREATE TABLE IF NOT EXISTS history (
            id       INTEGER PRIMARY KEY AUTOINCREMENT,
            video_id TEXT NOT NULL,
            played_at INTEGER
        );
        """)


def _now():
    return int(time.time())


def _item(row):
    return {"kind": "song", "id": row["video_id"], "title": row["title"] or "",
            "subtitle": row["subtitle"] or "", "thumb": row["thumb"] or "",
            "dur": int(row["dur"] or 0)}


def remember(c, t):
    """Simpan/refresh metadata lagu."""
    vid = (t.get("videoId") or "").strip()
    if not vid:
        return None
    c.execute("""INSERT INTO tracks (video_id,title,subtitle,thumb,dur,seen_at)
                 VALUES (?,?,?,?,?,?)
                 ON CONFLICT(video_id) DO UPDATE SET
                   title=excluded.title, subtitle=excluded.subtitle,
                   thumb=excluded.thumb, dur=excluded.dur, seen_at=excluded.seen_at""",
              (vid, t.get("title") or "", t.get("subtitle") or "",
               t.get("thumb") or "", int(t.get("dur") or 0), _now()))
    return vid


# ---------- favorit ----------
def fav_add(t):
    with _conn() as c:
        vid = remember(c, t)
        if not vid:
            return False
        c.execute("INSERT OR REPLACE INTO favorites (video_id, added_at) VALUES (?,?)", (vid, _now()))
        return True


def fav_remove(vid):
    with _conn() as c:
        c.execute("DELETE FROM favorites WHERE video_id=?", (vid,))
        return True


def fav_has(vid):
    with _conn() as c:
        return c.execute("SELECT 1 FROM favorites WHERE video_id=?", (vid,)).fetchone() is not None


def favs():
    with _conn() as c:
        rows = c.execute("""SELECT t.* FROM favorites f JOIN tracks t USING(video_id)
                            ORDER BY f.added_at DESC""").fetchall()
        return [_item(r) for r in rows]


# ---------- riwayat ----------
def history_add(t):
    with _conn() as c:
        vid = remember(c, t)
        if not vid:
            return False
        c.execute("INSERT INTO history (video_id, played_at) VALUES (?,?)", (vid, _now()))
        # buang duplikat berurutan supaya daftar tidak penuh lagu sama
        c.execute("""DELETE FROM history WHERE id NOT IN
                     (SELECT MAX(id) FROM history GROUP BY video_id)""")
        return True


def history(limit=100):
    with _conn() as c:
        rows = c.execute("""SELECT t.*, h.played_at FROM history h JOIN tracks t USING(video_id)
                            ORDER BY h.played_at DESC LIMIT ?""", (limit,)).fetchall()
        out = []
        for r in rows:
            it = _item(r)
            it["played_at"] = r["played_at"]
            out.append(it)
        return out


def history_clear():
    with _conn() as c:
        c.execute("DELETE FROM history")
        return True


# ---------- playlist ----------
def playlists():
    with _conn() as c:
        rows = c.execute("""SELECT p.id, p.name, COUNT(i.video_id) AS n
                            FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id
                            GROUP BY p.id ORDER BY p.created_at DESC""").fetchall()
        return [{"kind": "playlist", "id": str(r["id"]), "title": r["name"],
                 "subtitle": ("%d lagu" % r["n"]), "thumb": "", "dur": 0,
                 "count": r["n"]} for r in rows]


def playlist_create(name):
    name = (name or "").strip()[:80]
    if not name:
        return None
    with _conn() as c:
        cur = c.execute("INSERT INTO playlists (name, created_at) VALUES (?,?)", (name, _now()))
        return {"kind": "playlist", "id": str(cur.lastrowid), "title": name,
                "subtitle": "0 lagu", "thumb": "", "dur": 0, "count": 0}


def playlist_delete(pid):
    with _conn() as c:
        c.execute("DELETE FROM playlist_items WHERE playlist_id=?", (pid,))
        c.execute("DELETE FROM playlists WHERE id=?", (pid,))
        return True


def playlist_rename(pid, name):
    name = (name or "").strip()[:80]
    if not name:
        return False
    with _conn() as c:
        c.execute("UPDATE playlists SET name=? WHERE id=?", (name, pid))
        return True


def playlist_items(pid):
    with _conn() as c:
        rows = c.execute("""SELECT t.* FROM playlist_items i JOIN tracks t USING(video_id)
                            WHERE i.playlist_id=? ORDER BY i.position, i.added_at""", (pid,)).fetchall()
        return [_item(r) for r in rows]


def playlist_add(pid, t):
    with _conn() as c:
        vid = remember(c, t)
        if not vid:
            return False
        nxt = c.execute("SELECT COALESCE(MAX(position),0)+1 FROM playlist_items WHERE playlist_id=?",
                        (pid,)).fetchone()[0]
        c.execute("""INSERT OR REPLACE INTO playlist_items (playlist_id, video_id, position, added_at)
                     VALUES (?,?,?,?)""", (pid, vid, nxt, _now()))
        return True


def playlist_remove(pid, vid):
    with _conn() as c:
        c.execute("DELETE FROM playlist_items WHERE playlist_id=? AND video_id=?", (pid, vid))
        return True


# ---------- pencarian lokal ----------
def search(q, limit=60):
    like = "%" + (q or "").strip() + "%"
    if len(like) < 4:
        return []
    with _conn() as c:
        rows = c.execute("""SELECT * FROM tracks WHERE title LIKE ? OR subtitle LIKE ?
                            ORDER BY seen_at DESC LIMIT ?""", (like, like, limit)).fetchall()
        return [_item(r) for r in rows]


def stats():
    with _conn() as c:
        return {
            "favorites": c.execute("SELECT COUNT(*) FROM favorites").fetchone()[0],
            "playlists": c.execute("SELECT COUNT(*) FROM playlists").fetchone()[0],
            "history": c.execute("SELECT COUNT(*) FROM history").fetchone()[0],
            "tracks": c.execute("SELECT COUNT(*) FROM tracks").fetchone()[0],
        }
