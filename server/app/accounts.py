"""accounts.py - akun profil: username + sandi, sesi login, alias.

Kenapa ada: sebelumnya profil hanya header X-Profile biasa, jadi siapa pun yang
tahu PIN server bisa membaca dan mengubah data profil mana pun cukup dengan
mengganti nilai header itu. Modul ini menutup lubang tersebut - profil sekarang
ditentukan oleh token sesi hasil login, bukan oleh teks yang bisa dipalsukan.

Aturan yang dijaga:
- Sandi TIDAK PERNAH disimpan apa adanya. Yang disimpan salt + hash PBKDF2
  (200.000 iterasi), supaya database yang bocor tidak langsung memberi sandi.
- Kunci master (PIN server) dipakai untuk operasi admin: mendaftar akun baru dan
  mengadopsi profil lama. Tanpa itu, siapa pun yang tahu alamat server bisa
  membuat akun sendiri.
- Token sesi disimpan sebagai hash juga, bukan token mentah, supaya database yang
  bocor tidak bisa dipakai untuk masuk.
"""
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time

DB_PATH = os.getenv("YTM_LIBRARY", "/opt/ytm-relay/library.db")

_ITER = 200_000
_SESSION_DAYS = 90
_USER_RE = re.compile(r"^[A-Za-z0-9_.-]{3,24}$")


def _conn():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def _now():
    return int(time.time())


def init():
    """Buat tabel kalau belum ada. Aman dipanggil berulang (idempoten)."""
    with _conn() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS accounts (
            username   TEXT PRIMARY KEY,
            profile    TEXT UNIQUE NOT NULL,
            pass_hash  TEXT NOT NULL,
            salt       TEXT NOT NULL,
            alias      TEXT,
            created_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS sessions (
            token_hash TEXT PRIMARY KEY,
            username   TEXT NOT NULL,
            created_at INTEGER,
            expires_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(username);
        """)


# ---------- sandi ----------

def _hash_password(password: str, salt: str) -> str:
    return hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt.encode("utf-8"), _ITER
    ).hex()


def _hash_token(token: str) -> str:
    """Token sesi juga di-hash: yang tersimpan bukan token mentah."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _check_password(password: str, salt: str, expected: str) -> bool:
    return hmac.compare_digest(_hash_password(password, salt), expected)


# ---------- validasi ----------

def valid_username(u) -> bool:
    return bool(_USER_RE.match(str(u or "")))


def _alias(a, fallback):
    a = re.sub(r"[^A-Za-z0-9 _.\-]", "", str(a or "")).strip()[:24]
    return a or fallback


def _profile_for(username: str) -> str:
    """Profil deterministik dari username, jadi tidak dobel antar pengguna."""
    return "acc-" + re.sub(r"[^a-z0-9]", "", username.lower())[:24]


# ---------- daftar / masuk ----------

def register(username, password, alias, master_ok):
    """Buat akun baru. master_ok wajib True - mendaftar adalah operasi admin."""
    if not master_ok:
        raise PermissionError("Kunci master diperlukan untuk membuat akun")
    username = str(username or "").strip()
    if not valid_username(username):
        raise ValueError("Username 3-24 karakter, hanya huruf/angka/titik/strip/garis bawah")
    if not password or len(str(password)) < 6:
        raise ValueError("Sandi minimal 6 karakter")
    salt = secrets.token_hex(16)
    profile = _profile_for(username)
    with _conn() as c:
        if c.execute("SELECT 1 FROM accounts WHERE username=?", (username,)).fetchone():
            raise ValueError("Username sudah dipakai")
        c.execute(
            "INSERT INTO accounts (username,profile,pass_hash,salt,alias,created_at)"
            " VALUES (?,?,?,?,?,?)",
            (username, profile, _hash_password(str(password), salt), salt,
             _alias(alias, username), _now()))
    return {"username": username, "profile": profile, "alias": _alias(alias, username)}


def login(username, password):
    with _conn() as c:
        row = c.execute("SELECT * FROM accounts WHERE username=?",
                        (str(username or "").strip(),)).fetchone()
    if not row or not _check_password(str(password or ""), row["salt"], row["pass_hash"]):
        return None
    return _new_session(row["username"])


def _new_session(username: str) -> dict:
    token = secrets.token_urlsafe(32)
    now = _now()
    exp = now + _SESSION_DAYS * 86400
    with _conn() as c:
        c.execute("DELETE FROM sessions WHERE expires_at < ?", (now,))
        c.execute("INSERT INTO sessions (token_hash,username,created_at,expires_at)"
                  " VALUES (?,?,?,?)", (_hash_token(token), username, now, exp))
        row = c.execute("SELECT alias, profile FROM accounts WHERE username=?",
                        (username,)).fetchone()
    return {"token": token, "username": username,
            "profile": row["profile"], "alias": row["alias"] or username,
            "expires_at": exp}


def me(token):
    """Kembalikan data akun untuk token yang sah, atau None."""
    if not token:
        return None
    with _conn() as c:
        row = c.execute(
            "SELECT s.username, s.expires_at, a.profile, a.alias FROM sessions s"
            " JOIN accounts a ON a.username = s.username"
            " WHERE s.token_hash=?", (_hash_token(str(token)),)).fetchone()
        if not row:
            return None
        if row["expires_at"] < _now():
            c.execute("DELETE FROM sessions WHERE token_hash=?", (_hash_token(str(token)),))
            return None
        return {"username": row["username"], "profile": row["profile"],
                "alias": row["alias"] or row["username"]}


def logout(token):
    if not token:
        return
    with _conn() as c:
        c.execute("DELETE FROM sessions WHERE token_hash=?", (_hash_token(str(token)),))


def set_alias(username, alias):
    with _conn() as c:
        c.execute("UPDATE accounts SET alias=? WHERE username=?",
                  (_alias(alias, username), username))
        row = c.execute("SELECT alias FROM accounts WHERE username=?", (username,)).fetchone()
    return row["alias"] if row else None


def list_accounts():
    """Untuk admin/diagnosa: daftar akun beserta aliasnya."""
    with _conn() as c:
        return [dict(r) for r in c.execute(
            "SELECT username, alias, profile, created_at FROM accounts"
            " ORDER BY created_at")]


# ---------- migrasi ----------

_TABLES = ("favorites", "playlists", "playlist_items", "search_items",
           "searches", "history", "profiles")


def adopt(old_profile, username, master_ok):
    """Pindahkan seluruh data profil lama ke akun ini.

    Perlu kunci master karena memindahkan data orang lain adalah operasi berisiko.
    Aman dipanggil berulang: baris yang sudah pindah tidak membuat dobel.
    """
    if not master_ok:
        raise PermissionError("Kunci master diperlukan untuk mengadopsi profil")
    old = str(old_profile or "").strip()
    if not old:
        raise ValueError("Profil lama tidak boleh kosong")
    with _conn() as c:
        row = c.execute("SELECT profile FROM accounts WHERE username=?",
                        (username,)).fetchone()
        if not row:
            raise ValueError("Akun tidak ditemukan")
        new = row["profile"]
        if old == new:
            return {"moved": 0, "profile": new}
        moved = 0
        # playlist: pindahkan induk lalu anak-anaknya, tanpa menduplikasi id
        c.execute("UPDATE OR IGNORE playlists SET profile=? WHERE profile=?", (new, old))
        for pl in c.execute("SELECT id FROM playlists WHERE profile=?", (new,)).fetchall():
            pid = pl["id"]
            for it in c.execute(
                    "SELECT video_id, position, added_at FROM playlist_items"
                    " WHERE playlist_id=?", (pid,)).fetchall():
                c.execute(
                    "INSERT OR IGNORE INTO playlist_items (playlist_id,video_id,position,added_at)"
                    " VALUES (?,?,?,?)",
                    (pid, it["video_id"], it["position"], it["added_at"]))
                moved += 1
        c.execute("DELETE FROM playlists WHERE profile=?", (old,))
        for tbl in ("favorites", "search_items", "searches", "history"):
            c.execute(f"UPDATE OR IGNORE {tbl} SET profile=? WHERE profile=?", (new, old))
            c.execute(f"DELETE FROM {tbl} WHERE profile=?", (old,))
        c.execute("UPDATE OR IGNORE profiles SET id=? WHERE id=?", (new, old))
        c.execute("DELETE FROM profiles WHERE id=?", (old,))
    return {"moved": moved, "profile": new}
