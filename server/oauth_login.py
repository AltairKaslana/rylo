"""oauth_login.py - login OAuth device flow untuk YT Music (tanpa cookie browser).

Pakai:
    python oauth_login.py <client_id> <client_secret> [file_output]

Alur:
 1. minta kode ke Google, cetak URL + kode 8 karakter
 2. pengguna membuka URL dan mengetik kode
 3. skrip menunggu sampai disetujui, lalu menyimpan token (auto-refresh) ke oauth.json

Token yang disimpan berisi refresh_token, jadi tidak perlu login ulang.
"""
import json
import os
import sys
import time

from ytmusicapi.auth.oauth import OAuthCredentials

OUT = sys.argv[3] if len(sys.argv) > 3 else "/opt/ytm-relay/oauth.json"


def main():
    if len(sys.argv) < 3:
        print("pakai: oauth_login.py <client_id> <client_secret> [output]")
        return 2
    cid, secret = sys.argv[1].strip(), sys.argv[2].strip()
    creds = OAuthCredentials(cid, secret)

    code = creds.get_code()
    url = code.get("verification_url") or code.get("verification_uri") or "https://google.com/device"
    user_code = code.get("user_code", "")
    device_code = code.get("device_code", "")
    interval = int(code.get("interval", 5) or 5)
    expires = int(code.get("expires_in", 1800) or 1800)

    print("URL   :", url)
    print("KODE  :", user_code)
    print("batas : %d menit" % (expires // 60))
    sys.stdout.flush()

    deadline = time.time() + expires
    tok = None
    last = ""
    while time.time() < deadline:
        pending = None
        try:
            r = creds.token_from_code(device_code)
        except Exception as e:  # noqa: BLE001
            r = {"error": type(e).__name__, "error_description": str(e)}
        # Google mengembalikan error sebagai dict, bukan exception
        if isinstance(r, dict) and r.get("error"):
            err = str(r.get("error"))
            low = (err + " " + str(r.get("error_description", ""))).lower()
            if "authorization_pending" in low or "slow_down" in low:
                pending = "slow_down" if "slow_down" in low else "pending"
            elif "expired_token" in low or "access_denied" in low:
                print("GAGAL:", err, r.get("error_description", ""))
                return 3
            else:
                print("GAGAL:", err, r.get("error_description", ""))
                return 3
        else:
            tok = r
            break
        if last != pending:
            print("menunggu persetujuan...")
            sys.stdout.flush()
            last = pending
        time.sleep(interval + (5 if pending == "slow_down" else 0))
    if tok is None:
        print("GAGAL: kode kedaluwarsa sebelum disetujui")
        return 4

    data = dict(tok) if isinstance(tok, dict) else json.loads(json.dumps(tok))
    data.setdefault("scope", "https://www.googleapis.com/auth/youtube")
    tmp = OUT + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, OUT)
    try:
        os.chmod(OUT, 0o600)
    except OSError:
        pass
    print("TERSIMPAN:", OUT, "| refresh_token:", "ada" if data.get("refresh_token") else "TIDAK ADA")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
