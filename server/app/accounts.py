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
_INVITE_DAYS = int(os.getenv("YTM_INVITE_DAYS", "14"))
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
        CREATE TABLE IF NOT EXISTS invites (
            code_hash  TEXT PRIMARY KEY,
            created_at INTEGER,
            expires_at INTEGER,
            used_by    TEXT,
            used_at    INTEGER,
            note       TEXT
        );
        """)
        # kolom tambahan untuk mode publik (database lama tetap jalan)
        for col in ("ip TEXT", "invite_used TEXT"):
            try:
                c.execute(f"ALTER TABLE accounts ADD COLUMN {col}")
            except sqlite3.OperationalError:
                pass  # kolom sudah ada



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

def register(username, password, alias, master_ok, invite_ok=False, ip=None):
    """Buat akun baru. Butuh master_ok (admin) ATAU invite_ok (kode undangan terpakai).

    Di mode publik, jalur admin PIN master tetap ada supaya Tuan bisa membuat akun
    tanpa kode; yang berubah hanya: pendaftar umum wajib menukarkan kode undangan.
    """
    if not (master_ok or invite_ok):
        raise PermissionError("Kunci master diperlukan untuk membuat akun")
    username = str(username or "").strip()
    if not valid_username(username):
        raise ValueError("Username 3-24 karakter, hanya huruf/angka/titik/strip/garis bawah")
    if not password or len(str(password)) < 6:
        raise ValueError("Sandi minimal 6 karakter")
    salt = secrets.token_hex(16)
    profile = _profile_for(username)
    with _conn() as c:
        if c.execute("SELECT 1 FROM accounts WHERE username=?",
                     (username,)).fetchone():
            raise ValueError("Username sudah dipakai")
        c.execute(
            "INSERT INTO accounts (username,profile,pass_hash,salt,alias,created_at,ip)"
            " VALUES (?,?,?,?,?,?,?)",
            (username, profile, _hash_password(str(password), salt), salt,
             _alias(alias, username), _now(), str(ip or "")[:64]))
    return {"username": username, "profile": profile, "alias": _alias(alias, username)}


# ---------- kode undangan (mode publik) ----------

def _hash_code(code):
    return hashlib.sha256(str(code).encode("utf-8")).hexdigest()


def create_invite(master_ok, days=None, note=""):
    """Buat kode undangan baru. Kembalikan kode mentah SEKALI SAJA - yang disimpan hanya hash."""
    if not master_ok:
        raise PermissionError("Kunci master diperlukan untuk membuat kode undangan")
    days = days or _INVITE_DAYS
    code = secrets.token_urlsafe(9).replace("-", "").replace("_", "")[:12].upper()
    now = _now()
    with _conn() as c:
        c.execute(
            "INSERT INTO invites (code_hash,created_at,expires_at,used_by,used_at,note)"
            " VALUES (?,?,?,?,?,?)",
            (_hash_code(code), now, now + days * 86400, None, None, str(note)[:60]))
    return {"code": code, "expires_at": now + days * 86400, "days": days}


def redeem_invite(code, username, password, alias):
    """Tukarkan kode undangan: buat akun sekaligus tandai kode sudah terpakai.

    Dicek satu transaksi supaya dua orang tidak bisa memakai kode yang sama bersamaan.
    """
    ch = _hash_code(str(code or ""))
    now = _now()
    with _conn() as c:
        row = c.execute("SELECT * FROM invites WHERE code_hash=?", (ch,)).fetchone()
        if not row:
            raise PermissionError("Kode undangan tidak dikenal")
        if row["used_by"]:
            raise PermissionError("Kode undangan sudah dipakai")
        if row["expires_at"] < now:
            raise PermissionError("Kode undangan sudah kedaluwarsa")
        out = register(username, password, alias, master_ok=False,
                       invite_ok=True, ip=None)
        c.execute("UPDATE invites SET used_by=?, used_at=? WHERE code_hash=?",
                  (out["username"], now, ch))
    return out


def list_invites(master_ok):
    """Untuk admin: kode mana yang masih tersedia / sudah dipakai siapa."""
    if not master_ok:
        raise PermissionError("Kunci master diperlukan")
    now = _now()
    with _conn() as c:
        rows = c.execute(
            "SELECT created_at, expires_at, used_by, used_at, note FROM invites"
            " ORDER BY created_at DESC LIMIT 200").fetchall()
    out = []
    for r in rows:
        out.append({
            "dibuat": r["created_at"], "berlaku_sampai": r["expires_at"],
            "kedaluwarsa": r["expires_at"] < now,
            "dipakai_oleh": r["used_by"], "catatan": r["note"] or "",
        })
    return out


def count_signups_since(ip, since):
    """Berapa akun terdaftar dari satu IP sejak waktu `since` (batas pendaftaran)."""
    if not ip:
        return 0
    with _conn() as c:
        return c.execute(
            "SELECT COUNT(*) FROM accounts WHERE ip=? AND created_at>=?",
            (str(ip)[:64], int(since))).fetchone()[0]



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
        # Hitung dulu supaya angka yang dilaporkan jujur, bukan cuma item playlist.
        counts = {}
        for tbl in ("favorites", "search_items", "searches", "history", "playlists"):
            counts[tbl] = c.execute(
                f"SELECT COUNT(*) FROM {tbl} WHERE profile=?", (old,)).fetchone()[0]

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
        items_moved = c.execute(
            "SELECT COUNT(*) FROM playlist_items i JOIN playlists p ON p.id=i.playlist_id"
            " WHERE p.profile=?", (new,)).fetchone()[0]
        c.execute("DELETE FROM playlists WHERE profile=?", (old,))
        for tbl in ("favorites", "search_items", "searches", "history"):
            c.execute(f"UPDATE OR IGNORE {tbl} SET profile=? WHERE profile=?", (new, old))
            c.execute(f"DELETE FROM {tbl} WHERE profile=?", (old,))
        c.execute("UPDATE OR IGNORE profiles SET id=? WHERE id=?", (new, old))
        c.execute("DELETE FROM profiles WHERE id=?", (old,))

    total = sum(counts.values()) + items_moved
    detail = {k: v for k, v in counts.items() if v}
    if items_moved:
        detail["playlist_items"] = items_moved
    return {"moved": total, "detail": detail, "profile": new}
