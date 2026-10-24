#!/usr/bin/env python3
"""
Comskip for NextPVR recordings: finds finished recordings without commercial marks and scans
them, one at a time, writing the .edl beside the recording (NextPVR's recording.edl serves it,
and NextPVR deletes it with the recording).

Works from the files alone: the .xml NextPVR writes beside each recording says whether it's
finished (status READY), its channel and genres; no NextPVR sign-in is needed. Settings are in
nextpvr-comskip.conf beside this script (all optional; see DEFAULTS).

Run as a service (systemd user unit, see install.sh), or once by hand: --once [--dry-run].

While it runs it answers on http://<this machine>:<port>/:
  /                 a page: what it's scanning, what's waiting, what it found, its settings
  /api/status       JDVR's "scanning" and "scan failed" badges:
                    {"items": {"<recording file>": {"state": "scanning|found|none|queued|retry|failed", ...}}}
  /api/overview     everything the page shows
  /api/recording    one recording (?file=): whose marks, its state, whether it can be rescanned
  /api/rescan, /api/clear   (POST, unlocked) scan one again, or remove its marks (an empty .edl)
Looking is open to the network. Changes (settings, rescans) need the NextPVR PIN: the page signs in
to NextPVR through the scanner the way NextPVR's own clients do (a salted hash, so the PIN never
leaves the browser), and gets a token for half an hour.
"""

import argparse
import configparser
import hashlib
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent

DEFAULTS = {
    # Where NextPVR records; empty: read from NextPVR's config.xml.
    "recording_dirs": "",
    "nextpvr_config": "/var/opt/nextpvr/config.xml",
    "comskip": str(HERE / "comskip"),
    "comskip_ini": str(HERE / "comskip.ini"),
    # How often to look for new recordings, in seconds.
    "interval": "60",
    # A recording is only scanned once its file hasn't changed for this long, in seconds.
    "settle": "120",
    # Channels never scanned (names as NextPVR shows them, or channel numbers/OIDs), comma separated:
    # commercial-free channels.
    "skip_channels": "",
    # If set, only these channels are scanned.
    "only_channels": "",
    # Genres never scanned (e.g. News, Sports), comma separated.
    "skip_genres": "",
    # Shows never scanned (titles as NextPVR shows them), comma separated: one show on a channel
    # that's otherwise scanned.
    "skip_shows": "",
    # Recordings that finished before the scanner first ran are scanned too (oldest last).
    "backfill": "true",
    # Attempts before a recording is given up on (shown as failed).
    "max_attempts": "3",
    # CPU priority for Comskip (19 = lowest).
    "nice": "15",
    # Port for the page and JDVR's badges; 0: none.
    "port": "8867",
    # NextPVR, for checking the PIN when the page unlocks (and its channel list).
    "nextpvr_url": "http://127.0.0.1:8866",
}

# What the page can change (the rest stays as set in the file).
EDITABLE = ("skip_channels", "skip_genres", "skip_shows", "settle", "nice", "max_attempts", "backfill")

STATE = HERE / "nextpvr-comskip-state.json"
# Seconds before a failed scan is tried again.
RETRY_AFTER = 600
PAGE = HERE / "nextpvr-comskip.html"

# The state is shared with the page's server thread: changes and saves under this lock.
LOCK = threading.RLock()
# Set to look for work straight away (a rescan, new settings) rather than after the interval.
WAKE = threading.Event()


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def load_config(path):
    cp = configparser.ConfigParser()
    cp["scanner"] = DEFAULTS
    if path.exists():
        cp.read(path)
    return cp["scanner"]


def names(value):
    return {v.strip().lower() for v in value.split(",") if v.strip()}


def recording_dirs(cfg):
    dirs = [d.strip() for d in cfg["recording_dirs"].split(",") if d.strip()]
    if dirs:
        return dirs
    try:
        root = ET.parse(cfg["nextpvr_config"]).getroot()
    except (OSError, ET.ParseError) as e:
        log(f"Can't read NextPVR's settings ({e}); set recording_dirs")
        return []
    found = [e.text.strip() for e in root.iter("RecordingDirectory") if e.text and e.text.strip()]
    # Extra folders: "name~path|name~path" in NextPVR's format.
    for e in root.iter("ExtraRecordingDirectories"):
        for part in (e.text or "").split("|"):
            path = part.split("~")[-1].strip()
            if path:
                found.append(path)
    return found


def info(ts):
    """The recording's .xml, as a dict (status, channel, channel_OID, title, genres); None without one."""
    x = ts.with_suffix(".xml")
    try:
        root = ET.parse(x).getroot()
    except (OSError, ET.ParseError):
        return None
    d = {c.tag: (c.text or "").strip() for c in root}
    d["genres"] = [g.text.strip() for g in root.iter("genre") if g.text]
    return d


def load_state():
    try:
        return json.loads(STATE.read_text())
    except (OSError, ValueError):
        return {"started": time.time(), "items": {}}


def save_state(state):
    with LOCK:
        tmp = STATE.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, indent=1))
        tmp.replace(STATE)


def excluded(cfg, meta):
    """Why the settings leave this recording out (its .xml as from info()), or None."""
    if not meta:
        return None
    channel = {meta.get("channel", "").lower(), meta.get("channel_OID", "").lower()} - {""}
    if channel & names(cfg["skip_channels"]):
        return "channel switched off"
    only = names(cfg["only_channels"])
    if only and not channel & only:
        return "not in only_channels"
    if {g.lower() for g in meta["genres"]} & names(cfg["skip_genres"]):
        return "genre never scanned"
    if meta.get("title", "").lower() in names(cfg["skip_shows"]):
        return "show never scanned"
    return None


def candidates(cfg, state):
    """Finished recordings without an .edl that the settings allow, newest first."""
    settle = cfg.getint("settle")
    backfill = cfg.getboolean("backfill")
    max_attempts = cfg.getint("max_attempts")
    now = time.time()
    out = []
    for d in recording_dirs(cfg):
        for ts in Path(d).rglob("*.ts"):
            if ts.with_suffix(".edl").exists():
                continue
            try:
                mtime = ts.stat().st_mtime
            except OSError:
                continue
            # Still being recorded: NextPVR keeps a .ts.map beside it until it ends (and only writes
            # the .xml then).
            if now - mtime < settle or Path(str(ts) + ".map").exists():
                continue
            meta = info(ts)
            # Still being recorded (or failed): NextPVR marks a finished one READY.
            if meta is not None and meta.get("status", "READY").upper() != "READY":
                continue
            if not backfill and mtime < state["started"]:
                continue
            item = state["items"].get(str(ts), {})
            if item.get("attempts", 0) >= max_attempts:
                continue
            # A failed scan waits a while before the next try (the same moment would fail the same way).
            if item.get("state") == "retry" and now - item.get("finished", 0) < RETRY_AFTER:
                continue
            if excluded(cfg, meta):
                continue
            out.append((mtime, ts))
    return [ts for _, ts in sorted(out, reverse=True)]


def scan(cfg, ts, state):
    key = str(ts)
    with LOCK:
        item = state["items"].setdefault(key, {})
        item["attempts"] = item.get("attempts", 0) + 1
        item["state"] = "scanning"
        item["since"] = time.time()
        try:
            item["bytes"] = ts.stat().st_size
        except OSError:
            pass
        item.pop("error", None)
        save_state(state)
    log(f"Scanning {ts.name} (attempt {item['attempts']})")
    cmd = ["nice", "-n", cfg["nice"], cfg["comskip"], f"--ini={cfg['comskip_ini']}", f"--output={ts.parent}", str(ts)]
    t0 = time.time()
    try:
        r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=4 * 3600)
        code, err = r.returncode, r.stderr.strip().splitlines()[-1:] if r.stderr else []
    except (OSError, subprocess.TimeoutExpired) as e:
        code, err = -1, [str(e)]
    edl = ts.with_suffix(".edl")
    # Comskip: 0 = commercials found, 1 = none found; either way the scan is done.
    with LOCK:
        if code in (0, 1):
            if not edl.exists():
                edl.write_text("")
            breaks = [l for l in edl.read_text().splitlines() if l.strip()]
            item.update(state="found" if breaks else "none", breaks=len(breaks), finished=time.time())
            # How fast scans go (bytes and seconds so far), for the page's time left.
            if item.get("bytes"):
                b, sec = state.get("speed", [0, 0])
                state["speed"] = [b + item["bytes"], sec + (item["finished"] - item["since"])]
            log(f"Done in {time.time() - t0:.0f} s: {len(breaks)} break(s) in {ts.name}")
        else:
            failed = item["attempts"] >= cfg.getint("max_attempts")
            item.update(state="failed" if failed else "retry", error=" ".join(err) or f"exit {code}", finished=time.time())
            log(f"Comskip failed on {ts.name} (exit {code}{': ' + ' '.join(err) if err else ''})" + ("; giving up" if failed else "; will retry"))
        save_state(state)


def tidy(state):
    """Forget recordings that are gone (deleted in NextPVR)."""
    with LOCK:
        gone = [k for k in state["items"] if not Path(k).exists()]
        for k in gone:
            del state["items"][k]
        if gone:
            save_state(state)


# ---- the page --------------------------------------------------------------------------------


class Run:
    """What the main loop and the page share."""

    config_path = None
    cfg = None
    state = None
    # Waiting to be scanned (from the last look), and when the next look is.
    queue = []
    next_look = 0.0


def edl_seconds(ts):
    """Total length of the commercials in a recording's .edl."""
    total = 0.0
    try:
        for line in ts.with_suffix(".edl").read_text().splitlines():
            parts = line.split()
            if len(parts) >= 2:
                total += max(0.0, float(parts[1]) - float(parts[0]))
    except (OSError, ValueError):
        pass
    return round(total)


def describe(ts):
    """A recording as the page shows it, from its .xml (or its file name without one)."""
    meta = info(ts) or {}
    episode = ""
    if meta.get("season") and meta.get("episode"):
        episode = f"S{meta['season']}E{meta['episode']}"
    if meta.get("subtitle"):
        episode = f"{episode} · {meta['subtitle']}" if episode else meta["subtitle"]
    return {
        "file": str(ts),
        "show": meta.get("title") or ts.stem,
        "episode": episode,
        "channel": meta.get("channel", ""),
        "start": meta.get("startTime", ""),
        "end": meta.get("endTime", ""),
        "genres": meta.get("genres", []),
    }


def recording_now(cfg):
    """Recordings still being written (NextPVR keeps a .ts.map beside them)."""
    out = []
    for d in recording_dirs(cfg):
        for m in Path(d).rglob("*.ts.map"):
            out.append(describe(Path(str(m)[: -len(".map")]))["show"])
    return out


def overview(unlocked):
    cfg, state = Run.cfg, Run.state
    with LOCK:
        items = {k: dict(v) for k, v in state["items"].items()}
    history, now = [], None
    b, sec = state.get("speed", [0, 0])
    rate = b / sec if sec > 0 else 0
    for key, v in items.items():
        ts = Path(key)
        row = describe(ts) | {
            "state": v.get("state"),
            # A queued one the settings now leave out: says why rather than waiting forever.
            "skipped": excluded(cfg, info(ts)) if v.get("state") == "queued" else None,
            "breaks": v.get("breaks", 0),
            "ads": edl_seconds(ts) if v.get("state") == "found" else 0,
            "attempts": v.get("attempts", 0),
            "error": v.get("error", ""),
            "since": v.get("since"),
            "finished": v.get("finished"),
            "took": round(v["finished"] - v["since"]) if v.get("finished") and v.get("since") and v.get("state") != "scanning" and not v.get("cleared") else None,
            # Marks removed by hand (JDVR's Remove commercial marks): left alone until rescanned.
            "cleared": bool(v.get("cleared")),
            "bytes": v.get("bytes"),
        }
        if v.get("state") == "scanning":
            row["estimate"] = round(v["bytes"] / rate) if rate and v.get("bytes") else None
            now = row
        else:
            history.append(row)
    history.sort(key=lambda r: r.get("finished") or 0, reverse=True)
    channels = state.get("channels") or sorted({r["channel"] for r in history if r["channel"]})
    settings = {k: cfg[k] for k in EDITABLE}
    return {
        "host": socket.gethostname(),
        "dirs": recording_dirs(cfg),
        "interval": cfg.getint("interval"),
        "next_look": max(0, round(Run.next_look - time.time())),
        "now": now,
        "queue": [describe(ts) for ts in Run.queue if not now or str(ts) != now["file"]],
        "recording": recording_now(cfg),
        "counts": {s: sum(1 for r in history if r["state"] == s) for s in ("found", "none", "failed", "retry", "queued")},
        "history": history,
        "settings": settings,
        "only_channels": cfg["only_channels"],
        "channels": channels,
        "shows": sorted({r["show"] for r in history} | {q["show"] for q in map(describe, Run.queue)}),
        "comskip": comskip_info(cfg),
        "unlocked": unlocked,
    }


_version = {}


def comskip_version(path):
    """Comskip's first line ("Comskip 0.83.001, made using ffmpeg"), cached until the file changes."""
    try:
        key = (path, Path(path).stat().st_mtime)
    except OSError:
        return "not found"
    if key not in _version:
        try:
            r = subprocess.run([path, "--version"], capture_output=True, text=True, timeout=15)
            lines = [l.strip() for l in (r.stderr + "\n" + r.stdout).splitlines() if l.strip()]
            # Its name line goes to one stream and the rest to the other.
            _version[key] = next((l for l in lines if l.lower().startswith("comskip")), lines[0] if lines else "unknown")
        except (OSError, subprocess.TimeoutExpired) as e:
            _version[key] = f"won't run: {e}"
    return _version[key]


def writes_edl(text):
    """Whether an ini has Comskip write the .edl (output_edl=1): without it there are no marks."""
    m = re.findall(r"^\s*output_edl\s*=\s*(\d+)", text, re.M | re.I)
    return bool(m) and m[-1] != "0"


def ini_files():
    """The .ini files in the scanner's folder: the ones the page can pick between."""
    return sorted(p.name for p in HERE.glob("*.ini"))


def comskip_info(cfg):
    ini = Path(cfg["comskip_ini"])
    try:
        text = ini.read_text(errors="replace")
    except OSError:
        text = None
    previous = Run.state.get("ini_previous")
    return {
        "path": cfg["comskip"],
        "version": comskip_version(cfg["comskip"]),
        "ini": str(ini),
        "ini_name": ini.name if ini.parent == HERE else None,
        "ini_found": text is not None,
        "ini_writes_edl": writes_edl(text or ""),
        "inis": ini_files(),
        "previous": Path(previous).name if previous and Path(previous).exists() and previous != str(ini) else None,
        "folder": str(HERE),
    }


def use_ini(path):
    """Switches Comskip to another ini (in the scanner's folder), remembering the one before."""
    with LOCK:
        current = Run.cfg["comskip_ini"]
        if str(path) != current:
            Run.state["ini_previous"] = current
            save_state(Run.state)
        write_settings(Run.config_path, {"comskip_ini": str(path)})
        Run.cfg = load_config(Run.config_path)
    log(f"Comskip ini is now {path} (was {current})")


def write_settings(path, values):
    """Sets keys in the .conf, keeping its comments: a "#key =" or "key =" line becomes "key = value"."""
    lines = path.read_text().splitlines() if path.exists() else ["[scanner]"]
    if not any(l.strip() == "[scanner]" for l in lines):
        lines.insert(0, "[scanner]")
    for key, value in values.items():
        value = str(value).replace("\n", " ").strip()
        pattern = re.compile(rf"^\s*#?\s*{re.escape(key)}\s*=")
        for i, line in enumerate(lines):
            if pattern.match(line):
                lines[i] = f"{key} = {value}"
                break
        else:
            lines.append(f"{key} = {value}")
    tmp = path.with_suffix(".tmp")
    tmp.write_text("\n".join(lines) + "\n")
    tmp.replace(path)


class NextPvr:
    """Checks the PIN the NextPVR way: a session and its salt, then md5(":" + md5(PIN) + ":" + salt)."""

    # Sessions handed to the page to sign in with, by challenge id: (sid, when).
    pending = {}
    # Tokens for changes: token → expiry.
    tokens = {}
    failures = []

    @staticmethod
    def get(cfg, method, **params):
        q = urllib.parse.urlencode({"method": method, "format": "json"} | params)
        with urllib.request.urlopen(f"{cfg['nextpvr_url'].rstrip('/')}/service?{q}", timeout=10) as r:
            return json.loads(r.read())

    @classmethod
    def challenge(cls, cfg):
        r = cls.get(cfg, "session.initiate", ver="1.0", device="Comskip scanner")
        cid = secrets.token_urlsafe(12)
        now = time.time()
        with LOCK:
            cls.pending = {k: v for k, v in cls.pending.items() if now - v[1] < 120}
            cls.pending[cid] = (r["sid"], now)
        return {"challenge": cid, "salt": r["salt"]}

    @classmethod
    def login(cls, cfg, cid, md5):
        now = time.time()
        with LOCK:
            cls.failures = [t for t in cls.failures if now - t < 300]
            if len(cls.failures) >= 5:
                return None, "Too many wrong PINs: wait a few minutes."
            sid = cls.pending.pop(cid, (None, 0))[0]
        if not sid:
            return None, "That took too long: try again."
        r = cls.get(cfg, "session.login", sid=sid, md5=md5)
        if r.get("stat") != "ok":
            with LOCK:
                cls.failures.append(now)
            return None, "Wrong PIN."
        # While signed in: NextPVR's channel list, for the channel switches.
        try:
            chans = cls.get(cfg, "channel.list", sid=sid).get("channels", [])
            names = [c["channelName"] for c in chans if c.get("channelName")]
            if names:
                with LOCK:
                    Run.state["channels"] = names
                    save_state(Run.state)
        except (OSError, ValueError, KeyError):
            pass
        token = secrets.token_urlsafe(24)
        with LOCK:
            cls.tokens = {t: e for t, e in cls.tokens.items() if e > now}
            cls.tokens[token] = now + 1800
        return token, None

    @classmethod
    def valid(cls, token):
        now = time.time()
        with LOCK:
            if token and cls.tokens.get(token, 0) > now:
                cls.tokens[token] = now + 1800
                return True
        return False


def rescan(keys):
    """Scans these again: their .edl goes and they're queued (only recordings this scanner scanned,
    and only ones the settings still let it scan)."""
    n = 0
    with LOCK:
        for key in keys:
            item = Run.state["items"].get(key)
            if item is None or item.get("state") == "scanning":
                continue
            # Its marks would go and nothing would replace them.
            if excluded(Run.cfg, info(Path(key))):
                continue
            Path(key).with_suffix(".edl").unlink(missing_ok=True)
            Run.state["items"][key] = {"attempts": 0, "state": "queued"}
            n += 1
        save_state(Run.state)
    WAKE.set()
    return n


def recording_file(cfg, key):
    """The recording at this path, if it is one: a .ts inside NextPVR's recording folders."""
    ts = Path(key)
    if ts.suffix != ".ts" or not ts.is_file():
        return None
    real = ts.resolve()
    if not any(real.is_relative_to(Path(d).resolve()) for d in recording_dirs(cfg)):
        return None
    return ts


def why_not_rescan(key):
    """Why one recording can't be scanned again, or None: marks from another tool aren't the
    scanner's to replace, and ones the settings leave out would just lose their marks."""
    ts = recording_file(Run.cfg, key)
    if ts is None:
        return "That isn't a NextPVR recording."
    with LOCK:
        item = Run.state["items"].get(key)
    if item is None:
        return "Its marks come from another tool, not this scanner." if ts.with_suffix(".edl").exists() else "It hasn't been scanned yet: it's waiting its turn."
    if item.get("state") in ("scanning", "queued"):
        return "It's being scanned already." if item.get("state") == "scanning" else "It's already waiting to be scanned."
    reason = excluded(Run.cfg, info(ts))
    if reason:
        return f"The scanner's settings leave it out ({reason}), so its marks would just go."
    return None


def clear(key):
    """Removes a recording's commercial marks: an empty .edl, so NextPVR and JDVR see no ads and the
    scanner leaves it alone until it's scanned again. Returns why not, or None."""
    ts = recording_file(Run.cfg, key)
    if ts is None:
        return "That isn't a NextPVR recording."
    with LOCK:
        item = Run.state["items"].get(key)
        if item is None and ts.with_suffix(".edl").exists():
            return "Its marks come from another tool, not this scanner."
        if item and item.get("state") == "scanning":
            return "It's being scanned right now: try again when it's done."
        ts.with_suffix(".edl").write_text("")
        now = time.time()
        Run.state["items"][key] = {"attempts": (item or {}).get("attempts", 0), "state": "none", "breaks": 0, "cleared": True, "since": now, "finished": now}
        save_state(Run.state)
    log(f"Marks removed from {ts.name}")
    return None


class Handler(BaseHTTPRequestHandler):
    def send(self, code, body, kind="application/json"):
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def unlocked(self):
        return NextPvr.valid(self.headers.get("X-Token"))

    def do_GET(self):
        path = self.path.split("?")[0]
        try:
            if path == "/api/status":
                try:
                    body = STATE.read_bytes()
                except OSError:
                    body = b'{"items": {}}'
                self.send(200, body)
            elif path == "/api/overview":
                self.send(200, overview(self.unlocked()))
            elif path == "/api/unlock":
                self.send(200, NextPvr.challenge(Run.cfg))
            elif path == "/api/recording":
                # One recording, for JDVR's menu: whose marks it has and what can be done.
                key = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("file", [""])[0]
                ts = recording_file(Run.cfg, key)
                with LOCK:
                    item = dict(Run.state["items"].get(key) or {})
                self.send(200, {
                    "recording": ts is not None,
                    "edl": bool(ts and ts.with_suffix(".edl").exists()),
                    "ours": bool(item),
                    "state": item.get("state"),
                    "breaks": item.get("breaks", 0),
                    "cleared": bool(item.get("cleared")),
                    "rescan": why_not_rescan(key),
                })
            elif path == "/api/ini":
                body = Path(Run.cfg["comskip_ini"]).read_bytes()
                self.send(200, body, "text/plain; charset=utf-8")
            elif path in ("/", "/index.html"):
                self.send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            else:
                self.send_error(404)
        except OSError as e:
            self.send(502, {"error": f"NextPVR didn't answer: {e}"} if path == "/api/unlock" else {"error": str(e)})

    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(min(n, 1024 * 1024)) or b"{}")
        except ValueError:
            self.send(400, {"error": "Not JSON"})
            return
        if path == "/api/unlock":
            try:
                token, problem = NextPvr.login(Run.cfg, str(body.get("challenge", "")), str(body.get("md5", "")))
            except OSError as e:
                self.send(502, {"error": f"NextPVR didn't answer: {e}"})
                return
            self.send(200 if token else 403, {"token": token} if token else {"error": problem})
            return
        # Everything else changes something: the token from unlocking (a header, so another site
        # can't send it).
        if not self.unlocked():
            self.send(403, {"error": "Locked: unlock with the NextPVR PIN."})
            return
        if path == "/api/settings":
            values = {k: body[k] for k in EDITABLE if k in body}
            problems = [k for k in ("settle", "nice", "max_attempts") if k in values and not str(values[k]).isdigit()]
            if "backfill" in values:
                values["backfill"] = "true" if values["backfill"] in (True, "true") else "false"
            if problems:
                self.send(400, {"error": "Not a number: " + ", ".join(problems)})
                return
            with LOCK:
                write_settings(Run.config_path, values)
                Run.cfg = load_config(Run.config_path)
            log("Settings changed on the page: " + ", ".join(f"{k}={v}" for k, v in values.items()))
            WAKE.set()
            self.send(200, {"saved": list(values)})
        elif path == "/api/ini":
            self.ini(body)
        elif path == "/api/rescan":
            if body.get("all"):
                self.send(200, {"queued": rescan(list(Run.state["items"]))})
                return
            key = str(body.get("file", ""))
            reason = why_not_rescan(key)
            self.send(200, {"queued": 0 if reason else rescan([key]), "reason": reason})
        elif path == "/api/clear":
            reason = clear(str(body.get("file", "")))
            self.send(409 if reason else 200, {"error": reason} if reason else {"cleared": True})
        else:
            self.send_error(404)

    def ini(self, body):
        """Upload a new ini (it never overwrites one: a taken name gets a number), use one already in
        the folder, or go back to the one before. An ini that wouldn't write the .edl needs force."""
        action = body.get("action")
        if action == "previous":
            previous = Run.state.get("ini_previous")
            if not previous or not Path(previous).exists():
                self.send(400, {"error": "There's no previous ini to go back to."})
                return
            use_ini(previous)
            self.send(200, {"ini": Path(previous).name})
            return
        if action == "use":
            name = str(body.get("name", ""))
            if name not in ini_files():
                self.send(400, {"error": f"No {name} in {HERE}."})
                return
            target = HERE / name
            text = target.read_text(errors="replace")
        elif action == "upload":
            text = str(body.get("content", ""))
            stem = re.sub(r"[^A-Za-z0-9._ -]", "", Path(str(body.get("name", "comskip.ini"))).stem).strip(" .") or "comskip"
            if not text.strip() or len(text) > 512 * 1024 or "\0" in text:
                self.send(400, {"error": "That doesn't look like an ini file (empty, binary or over 512 KB)."})
                return
            if "[" not in text and "=" not in text:
                self.send(400, {"error": "That doesn't look like a Comskip ini: no settings in it."})
                return
            target = HERE / f"{stem}.ini"
            n = 2
            while target.exists():
                target = HERE / f"{stem} ({n}).ini"
                n += 1
        else:
            self.send(400, {"error": "Unknown action."})
            return
        if not writes_edl(text) and not body.get("force"):
            self.send(409, {"warning": f"{target.name} doesn't have output_edl=1, so Comskip wouldn't write the .edl: no marks in NextPVR or JDVR."})
            return
        if action == "upload":
            target.write_text(text)
        use_ini(target)
        self.send(200, {"ini": target.name})

    def log_message(self, *args):
        pass


def serve(port):
    try:
        server = ThreadingHTTPServer(("", port), Handler)
    except OSError as e:
        log(f"No page on port {port}: {e}")
        return
    threading.Thread(target=server.serve_forever, name="page", daemon=True).start()
    log(f"Page on port {port}: http://{socket.gethostname()}:{port}/")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", default=str(HERE / "nextpvr-comskip.conf"))
    ap.add_argument("--once", action="store_true", help="scan what's waiting, then stop")
    ap.add_argument("--dry-run", action="store_true", help="list what would be scanned")
    args = ap.parse_args()
    Run.config_path = Path(args.config)
    Run.cfg = load_config(Run.config_path)
    state = Run.state = load_state()
    # A scan cut short (stopped or restarted mid-scan) goes again.
    for item in state["items"].values():
        if item.get("state") == "scanning":
            item["state"] = "retry"
    save_state(state)
    if not args.dry_run and Run.cfg.getint("port"):
        serve(Run.cfg.getint("port"))
    log(f"Watching {', '.join(recording_dirs(Run.cfg)) or 'nothing'} every {Run.cfg['interval']} s")
    while True:
        # Settings changed in the file (or on the page) apply from the next look.
        with LOCK:
            Run.cfg = load_config(Run.config_path)
        cfg = Run.cfg
        tidy(state)
        waiting = Run.queue = candidates(cfg, state)
        if args.dry_run:
            for ts in waiting:
                print(ts)
            return
        if waiting:
            # One at a time, then look again: a newer recording goes first.
            scan(cfg, waiting[0], state)
            continue
        if args.once:
            return
        Run.next_look = time.time() + cfg.getint("interval")
        WAKE.wait(cfg.getint("interval"))
        WAKE.clear()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
