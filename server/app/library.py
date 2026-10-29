"""library.py - koleksi pengguna: favorit, riwayat, playlist. Mendukung banyak profil.

Setiap perangkat bisa memakai profilnya sendiri (header X-Profile). Metadata lagu
(tabel tracks) dipakai bersama supaya tidak dobel; favorit/playlist/riwayat terpisah
per profil. Jadi satu server bisa melayani beberapa HP tanpa datanya bercampur.
"""
import os
import re
import sqlite3
import time

DB_PATH = os.getenv("YTM_LIBRARY", "/opt/ytm-relay/library.db")


def _conn():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def clean_profile(p):
    p = re.sub(r"[^A-Za-z0-9_-]", "", str(p or ""))[:40]
    return p or "default"


def _now():
    return int(time.time())


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
        CREATE TABLE IF NOT EXISTS profiles (
            id         TEXT PRIMARY KEY,
            name       TEXT,
            created_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS favorites (
            profile  TEXT NOT NULL DEFAULT 'default',
            video_id TEXT NOT NULL,
            added_at INTEGER,
            PRIMARY KEY (profile, video_id)
        );
        CREATE TABLE IF NOT EXISTS playlists (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            profile    TEXT NOT NULL DEFAULT 'default',
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
        CREATE TABLE IF NOT EXISTS search_items (
            profile   TEXT NOT NULL DEFAULT 'default',
            query     TEXT NOT NULL,
            video_id  TEXT NOT NULL,
            title     TEXT,
            subtitle  TEXT,
            thumb     TEXT,
            dur       INTEGER DEFAULT 0,
            picked_at INTEGER,
            PRIMARY KEY (profile, query, video_id)
        );
        CREATE TABLE IF NOT EXISTS searches (
            profile   TEXT NOT NULL DEFAULT 'default',
            query     TEXT NOT NULL,
            kind      TEXT,
            searched_at INTEGER,
            video_id  TEXT,
            title     TEXT,
            subtitle  TEXT,
            thumb     TEXT,
            dur       INTEGER DEFAULT 0,
            PRIMARY KEY (profile, query)
        );
        CREATE TABLE IF NOT EXISTS history (
            id        INTEGER PRIMARY KEY AUTOINCREMENT,
            profile   TEXT NOT NULL DEFAULT 'default',
            video_id  TEXT NOT NULL,
            played_at INTEGER
        );
        """)
        c.execute("""CREATE TABLE IF NOT EXISTS search_items (
            profile   TEXT NOT NULL DEFAULT 'default',
            query     TEXT NOT NULL,
            video_id  TEXT NOT NULL,
            title     TEXT,
            subtitle  TEXT,
            thumb     TEXT,
            dur       INTEGER DEFAULT 0,
            picked_at INTEGER,
            PRIMARY KEY (profile, query, video_id)
        )""")
        # migrasi pencarian terakhir: tambah kolom lagu yang dipilih
        scols = [r["name"] for r in c.execute("PRAGMA table_info(searches)")]
        for col, ddl in (("video_id", "TEXT"), ("title", "TEXT"), ("subtitle", "TEXT"),
                         ("thumb", "TEXT"), ("dur", "INTEGER DEFAULT 0")):
            if col not in scols:
                c.execute("ALTER TABLE searches ADD COLUMN %s %s" % (col, ddl))
        # migrasi dari versi sebelum ada kolom profile
        for table in ("favorites", "playlists", "history"):
            cols = [r["name"] for r in c.execute("PRAGMA table_info(%s)" % table)]
            if "profile" not in cols:
                c.execute("ALTER TABLE %s ADD COLUMN profile TEXT NOT NULL DEFAULT 'default'" % table)
        c.execute("INSERT OR IGNORE INTO profiles (id,name,created_at) VALUES ('default','Utama',?)",
                  (_now(),))


def _item(row):
    return {"kind": "song", "id": row["video_id"], "title": row["title"] or "",
            "subtitle": row["subtitle"] or "", "thumb": row["thumb"] or "",
            "dur": int(row["dur"] or 0)}


def remember(c, t):
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


def touch_profile(profile, name=None):
    with _conn() as c:
        c.execute("INSERT OR IGNORE INTO profiles (id,name,created_at) VALUES (?,?,?)",
                  (profile, name or profile, _now()))
        if name:
            c.execute("UPDATE profiles SET name=? WHERE id=?", (name, profile))
        row = c.execute("SELECT id,name FROM profiles WHERE id=?", (profile,)).fetchone()
        return {"id": row["id"], "name": row["name"]}


# ---------- favorit ----------
def fav_add(profile, t):
    with _conn() as c:
        vid = remember(c, t)
        if not vid:
            return False
        c.execute("INSERT OR REPLACE INTO favorites (profile, video_id, added_at) VALUES (?,?,?)",
                  (profile, vid, _now()))
        return True


def fav_remove(profile, vid):
    with _conn() as c:
        c.execute("DELETE FROM favorites WHERE profile=? AND video_id=?", (profile, vid))
        return True


def fav_has(profile, vid):
    with _conn() as c:
        return c.execute("SELECT 1 FROM favorites WHERE profile=? AND video_id=?",
                         (profile, vid)).fetchone() is not None


def favs(profile):
    with _conn() as c:
        rows = c.execute("""SELECT t.* FROM favorites f JOIN tracks t USING(video_id)
                            WHERE f.profile=? ORDER BY f.added_at DESC""", (profile,)).fetchall()
        return [_item(r) for r in rows]


# ---------- riwayat ----------
def history_add(profile, t):
    with _conn() as c:
        vid = remember(c, t)
        if not vid:
            return False
        c.execute("INSERT INTO history (profile, video_id, played_at) VALUES (?,?,?)",
                  (profile, vid, _now()))
        c.execute("""DELETE FROM history WHERE profile=? AND id NOT IN
                     (SELECT MAX(id) FROM history WHERE profile=? GROUP BY video_id)""",
                  (profile, profile))
        return True


def history(profile, limit=100):
    with _conn() as c:
        rows = c.execute("""SELECT t.*, h.played_at FROM history h JOIN tracks t USING(video_id)
                            WHERE h.profile=? ORDER BY h.played_at DESC LIMIT ?""",
                         (profile, limit)).fetchall()
        out = []
        for r in rows:
            it = _item(r)
            it["played_at"] = r["played_at"]
            out.append(it)
        return out


def history_clear(profile):
    with _conn() as c:
        c.execute("DELETE FROM history WHERE profile=?", (profile,))
        return True


# ---------- playlist ----------
def playlists(profile):
    with _conn() as c:
        rows = c.execute("""SELECT p.id, p.name, COUNT(i.video_id) AS n
                            FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id = p.id
                            WHERE p.profile=? GROUP BY p.id ORDER BY p.created_at DESC""",
                         (profile,)).fetchall()
        return [{"kind": "playlist", "id": str(r["id"]), "title": r["name"],
                 "subtitle": ("%d lagu" % r["n"]), "thumb": "", "dur": 0,
                 "count": r["n"]} for r in rows]


def playlist_create(profile, name):
    name = (name or "").strip()[:80]
    if not name:
        return None
    with _conn() as c:
        cur = c.execute("INSERT INTO playlists (profile, name, created_at) VALUES (?,?,?)",
                        (profile, name, _now()))
        return {"kind": "playlist", "id": str(cur.lastrowid), "title": name,
                "subtitle": "0 lagu", "thumb": "", "dur": 0, "count": 0}


def _owns(c, profile, pid):
    return c.execute("SELECT 1 FROM playlists WHERE id=? AND profile=?",
                     (pid, profile)).fetchone() is not None


def playlist_delete(profile, pid):
    with _conn() as c:
        if not _owns(c, profile, pid):
            return False
        c.execute("DELETE FROM playlist_items WHERE playlist_id=?", (pid,))
        c.execute("DELETE FROM playlists WHERE id=?", (pid,))
        return True


def playlist_rename(profile, pid, name):
    name = (name or "").strip()[:80]
    if not name:
        return False
    with _conn() as c:
        if not _owns(c, profile, pid):
            return False
        c.execute("UPDATE playlists SET name=? WHERE id=?", (name, pid))
        return True


def playlist_items(profile, pid):
    with _conn() as c:
        if not _owns(c, profile, pid):
            return []
        rows = c.execute("""SELECT t.* FROM playlist_items i JOIN tracks t USING(video_id)
                            WHERE i.playlist_id=? ORDER BY i.position, i.added_at""",
                         (pid,)).fetchall()
        return [_item(r) for r in rows]


def playlist_add(profile, pid, t):
    with _conn() as c:
        if not _owns(c, profile, pid):
            return False
        vid = remember(c, t)
        if not vid:
            return False
        nxt = c.execute("SELECT COALESCE(MAX(position),0)+1 FROM playlist_items WHERE playlist_id=?",
                        (pid,)).fetchone()[0]
        c.execute("""INSERT OR REPLACE INTO playlist_items (playlist_id, video_id, position, added_at)
                     VALUES (?,?,?,?)""", (pid, vid, nxt, _now()))
        return True


def playlist_remove(profile, pid, vid):
    with _conn() as c:
        if not _owns(c, profile, pid):
            return False
        c.execute("DELETE FROM playlist_items WHERE playlist_id=? AND video_id=?", (pid, vid))
        return True


# ---------- pencarian lokal ----------
def search(profile, q, limit=60):
    like = "%" + (q or "").strip() + "%"
    if len(like) < 4:
        return []
    with _conn() as c:
        rows = c.execute("""
            SELECT DISTINCT t.* FROM tracks t
            WHERE (t.title LIKE ? OR t.subtitle LIKE ?)
              AND (t.video_id IN (SELECT video_id FROM favorites WHERE profile=?)
                OR t.video_id IN (SELECT video_id FROM history WHERE profile=?)
                OR t.video_id IN (SELECT i.video_id FROM playlist_items i
                                  JOIN playlists p ON p.id=i.playlist_id WHERE p.profile=?))
            ORDER BY t.seen_at DESC LIMIT ?""",
            (like, like, profile, profile, profile, limit)).fetchall()
        return [_item(r) for r in rows]


def search_add(profile, q, kind="songs", item=None):
    """Catat pencarian terakhir + lagu yang dipilih (biar bisa langsung diputar lagi).

    item: dict dengan video_id, title, subtitle, thumb, dur (opsional).
    """
    q = (q or "").strip()
    if not q:
        return
    item = item or {}
    vid = (item.get("video_id") or item.get("videoId") or "").strip()
    with _conn() as c:
        if vid:
            # riwayat lagu yang dipilih untuk pencarian ini (bisa lebih dari satu)
            c.execute("""INSERT INTO search_items
                         (profile, query, video_id, title, subtitle, thumb, dur, picked_at)
                         VALUES (?,?,?,?,?,?,?,?)
                         ON CONFLICT(profile, query, video_id) DO UPDATE SET
                            title=excluded.title, subtitle=excluded.subtitle,
                            thumb=excluded.thumb, dur=excluded.dur,
                            picked_at=excluded.picked_at""",
                      (profile, q, vid, item.get("title"), item.get("subtitle"),
                       item.get("thumb"), int(item.get("dur") or 0), _now()))
            # sisakan 8 lagu terbaru per pencarian
            c.execute("""DELETE FROM search_items WHERE profile=? AND query=? AND video_id NOT IN (
                            SELECT video_id FROM search_items WHERE profile=? AND query=?
                            ORDER BY picked_at DESC LIMIT 8)""", (profile, q, profile, q))
        c.execute("INSERT OR IGNORE INTO profiles (id,name,created_at) VALUES (?,'Utama',?)",
                  (profile, _now()))
        c.execute("""INSERT INTO searches (profile, query, kind, searched_at,
                                           video_id, title, subtitle, thumb, dur)
                     VALUES (?,?,?,?,?,?,?,?,?)
                     ON CONFLICT(profile, query) DO UPDATE SET
                        kind=excluded.kind, searched_at=excluded.searched_at,
                        video_id=excluded.video_id, title=excluded.title,
                        subtitle=excluded.subtitle, thumb=excluded.thumb,
                        dur=excluded.dur""",
                  (profile, q, kind or "songs", _now(),
                   vid or None,
                   item.get("title"), item.get("subtitle"), item.get("thumb"),
                   int(item.get("dur") or 0)))
        # sisakan 12 terbaru saja
        c.execute("""DELETE FROM searches WHERE profile=? AND query NOT IN (
                        SELECT query FROM searches WHERE profile=?
                        ORDER BY searched_at DESC LIMIT 12)""", (profile, profile))


def searches(profile, limit=12):
    """Daftar pencarian terakhir, terbaru lebih dulu."""
    with _conn() as c:
        rows = c.execute("""SELECT query, kind, searched_at FROM searches
                            WHERE profile=? ORDER BY searched_at DESC LIMIT ?""",
                         (profile, limit)).fetchall()
        out = []
        for r in rows:
            q = r["query"]
            # lagu dari riwayat (bisa banyak); kalau kosong, pakai kolom lama
            def _baris(r):
                # kind="song" wajib: tanpa itu aplikasi membuka halaman detail, bukan memutar
                return dict(kind="song", id=r["video_id"], title=r["title"],
                            subtitle=r["subtitle"], thumb=r["thumb"], dur=r["dur"] or 0)
            items = [_baris(x) for x in c.execute(
                """SELECT video_id, title, subtitle, thumb, dur FROM search_items
                   WHERE profile=? AND query=? ORDER BY picked_at DESC""",
                (profile, q)).fetchall()]
            if not items:
                lama = c.execute("""SELECT video_id, title, subtitle, thumb, dur
                                    FROM searches WHERE profile=? AND query=?""",
                                 (profile, q)).fetchone()
                if lama and lama["video_id"]:
                    items = [_baris(lama)]
            out.append(dict(query=q, kind=r["kind"] or "songs",
                            at=r["searched_at"], items=items))
    return out


def recent_songs(profile, limit=30):
    """Lagu yang pernah dipilih dari pencarian, rata & terbaru lebih dulu.

    Sengaja RATA, bukan dikelompokkan per pencarian: daftar pencarian dibatasi
    12 sementara setiap kata kunci yang diketik pun tercatat, jadi lagu yang
    dipilih sering terdorong keluar oleh pencarian kosong. Dengan menarik dari
    search_items langsung, lagu yang dipilih tidak bisa terdorong apa pun.
    `query` ikut dikembalikan karena menghapus satu lagu butuh pasangan
    (query, video_id).
    """
    with _conn() as c:
        rows = c.execute("""SELECT video_id, title, subtitle, thumb, dur, query, picked_at
                            FROM search_items WHERE profile=?
                            ORDER BY picked_at DESC LIMIT ?""",
                         (profile, limit)).fetchall()
        # kind="song" wajib: tanpa itu aplikasi membuka halaman detail, bukan memutar
        return [dict(kind="song", id=r["video_id"], title=r["title"],
                     subtitle=r["subtitle"], thumb=r["thumb"], dur=r["dur"] or 0,
                     query=r["query"], at=r["picked_at"]) for r in rows]


def search_item_remove(profile, q, vid):
    with _conn() as c:
        c.execute("DELETE FROM search_items WHERE profile=? AND query=? AND video_id=?",
                  (profile, q, vid))


def search_clear(profile):
    with _conn() as c:
        c.execute("DELETE FROM searches WHERE profile=?", (profile,))
        c.execute("DELETE FROM search_items WHERE profile=?", (profile,))


def search_remove(profile, q):
    with _conn() as c:
        c.execute("DELETE FROM searches WHERE profile=? AND query=?", (profile, q))
        c.execute("DELETE FROM search_items WHERE profile=? AND query=?", (profile, q))


def stats(profile):
    with _conn() as c:
        return {
            "profile": profile,
            "favorites": c.execute("SELECT COUNT(*) FROM favorites WHERE profile=?", (profile,)).fetchone()[0],
            "playlists": c.execute("SELECT COUNT(*) FROM playlists WHERE profile=?", (profile,)).fetchone()[0],
            "history": c.execute("SELECT COUNT(*) FROM history WHERE profile=?", (profile,)).fetchone()[0],
            "tracks": c.execute("SELECT COUNT(*) FROM tracks").fetchone()[0],
            "profiles": c.execute("SELECT COUNT(*) FROM profiles").fetchone()[0],
        }
