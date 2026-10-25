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
import urllib.error
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
    # Transcoding: once a recording has its marks, it's converted to a smaller format and NextPVR's
    # entry is switched to the new file (same recording: times, series, watched and resume stay).
    "transcode": "false",
    # hevc, h264 or av1.
    "transcode_codec": "hevc",
    # auto: the first one that works here (graphics chip first); or one by name (hevc_vaapi, libx265…).
    "transcode_encoder": "auto",
    # smaller, balanced or better.
    "transcode_quality": "balanced",
    # Shrink taller pictures to this height (720, 1080…); 0 keeps the size.
    "transcode_max_height": "0",
    # mkv or mp4.
    "transcode_container": "mkv",
    # copy (as recorded) or aac.
    "transcode_audio": "copy",
    # Days the original is kept (in .originals in the recording folder); 0 deletes it straight away.
    "transcode_keep_days": "2",
    # Only between these hours, e.g. 1-6; empty: any time.
    "transcode_hours": "",
    # Hours after a recording ends before it's transcoded (so one being watched isn't swapped).
    "transcode_wait_hours": "0",
    # Also recordings made before transcoding was switched on.
    "transcode_backfill": "false",
    "transcode_skip_channels": "",
    "transcode_skip_shows": "",
    # md5 of the NextPVR PIN, set from the page: switching NextPVR's entry to the new file needs a
    # NextPVR sign-in. It signs in as well as the PIN would, so keep this file to yourself.
    "nextpvr_pin_md5": "",
    "ffmpeg": "ffmpeg",
    "ffprobe": "ffprobe",
    "vaapi_device": "/dev/dri/renderD128",
}

# What the page can change (the rest stays as set in the file).
EDITABLE = ("skip_channels", "skip_genres", "skip_shows", "settle", "nice", "max_attempts", "backfill",
            "transcode", "transcode_codec", "transcode_encoder", "transcode_quality", "transcode_max_height",
            "transcode_container", "transcode_audio", "transcode_keep_days", "transcode_hours",
            "transcode_wait_hours", "transcode_backfill", "transcode_skip_channels", "transcode_skip_shows")

# Recording files the scanner looks at (NextPVR records .ts; transcoded ones are .mkv or .mp4).
VIDEO = (".ts", ".mkv", ".mp4")
# Originals kept after transcoding, in each recording folder.
ORIGINALS = ".originals"

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


def recordings(cfg, suffixes=VIDEO):
    """Recording files in NextPVR's folders: not the transcoder's work in progress (hidden names) or
    the originals it keeps."""
    for d in recording_dirs(cfg):
        for f in Path(d).rglob("*"):
            if f.suffix.lower() in suffixes and not f.name.startswith(".") and ORIGINALS not in f.parts and f.is_file():
                yield f


def candidates(cfg, state):
    """Finished recordings without an .edl that the settings allow, newest first."""
    settle = cfg.getint("settle")
    backfill = cfg.getboolean("backfill")
    max_attempts = cfg.getint("max_attempts")
    now = time.time()
    out = []
    for ts in recordings(cfg):
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
        jobs = state.get("transcode", {})
        gone_t = [k for k, v in jobs.items() if v.get("state") != "transcoding" and not Path(k).exists()]
        for k in gone_t:
            del jobs[k]
        if gone or gone_t:
            save_state(state)


# ---- transcoding -----------------------------------------------------------------------------

# (codec, encoder, kind), in the order "auto" tries them: the graphics chip first.
ENCODERS = [
    ("hevc", "hevc_vaapi", "vaapi"),
    ("hevc", "libx265", "cpu"),
    ("h264", "h264_vaapi", "vaapi"),
    ("h264", "libx264", "cpu"),
    ("av1", "av1_vaapi", "vaapi"),
    ("av1", "libsvtav1", "cpu"),
]
# Quality → the encoder's own number (qp for the graphics chip, crf for the CPU encoders; lower is
# better and bigger).
QUALITY = {
    "hevc_vaapi": {"smaller": 30, "balanced": 26, "better": 22},
    "h264_vaapi": {"smaller": 28, "balanced": 24, "better": 20},
    "av1_vaapi": {"smaller": 160, "balanced": 128, "better": 96},
    "libx265": {"smaller": 28, "balanced": 24, "better": 20},
    "libx264": {"smaller": 26, "balanced": 22, "better": 18},
    "libsvtav1": {"smaller": 40, "balanced": 34, "better": 28},
}
# Source codecs already as small as the chosen one: left as they are (unless they're too tall).
EFFICIENT = {"h264": {"h264", "hevc", "av1"}, "hevc": {"hevc", "av1"}, "av1": {"av1"}}


def video_args(cfg, encoder, kind, quality, filters):
    """ffmpeg's arguments for the video: filters (deinterlace, shrink) then the encoder."""
    q = str(QUALITY[encoder][quality if quality in QUALITY[encoder] else "balanced"])
    if kind == "vaapi":
        filters = filters + ["format=nv12", "hwupload"]
        # -sei 0: broadcast captions ride in the video and overflow the chip's buffer otherwise
        # ("Access unit too large"); they don't survive transcoding anyway.
        extra = ["-sei", "0"] if encoder in ("hevc_vaapi", "h264_vaapi") else []
        return ["-vf", ",".join(filters), "-c:v", encoder, "-qp", q] + extra
    preset = {"libx265": "fast", "libx264": "medium", "libsvtav1": "8"}[encoder]
    return (["-vf", ",".join(filters)] if filters else []) + ["-c:v", encoder, "-crf", q, "-preset", preset]


def ffmpeg_cmd(cfg, encoder, kind, src, dst, source):
    filters = []
    # Broadcast 1080i: only frames flagged interlaced are deinterlaced.
    if source.get("field_order") in ("tt", "bb", "tb", "bt"):
        filters.append("bwdif=mode=send_frame:deint=interlaced")
    max_h = cfg.getint("transcode_max_height")
    if max_h and source.get("height", 0) > max_h:
        filters.append(f"scale=-2:{max_h}")
    cmd = ["nice", "-n", cfg["nice"], cfg["ffmpeg"], "-hide_banner", "-nostdin", "-v", "error", "-y"]
    if kind == "vaapi":
        cmd += ["-vaapi_device", cfg["vaapi_device"]]
    cmd += ["-i", str(src), "-map", "0:v:0", "-map", "0:a?", "-max_muxing_queue_size", "4096"]
    cmd += video_args(cfg, encoder, kind, cfg["transcode_quality"], filters)
    cmd += ["-c:a", "aac"] if cfg["transcode_audio"] == "aac" else ["-c:a", "copy"]
    if dst.suffix == ".mp4":
        cmd += ["-movflags", "+faststart"]
    cmd += ["-progress", "pipe:1", "-nostats", str(dst)]
    return cmd


def probe(cfg, path):
    """The video's codec, size, field order and the file's duration (seconds); {} if unreadable."""
    try:
        r = subprocess.run([cfg["ffprobe"], "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=codec_name,width,height,field_order:format=duration", "-of", "json", str(path)],
                           capture_output=True, text=True, timeout=120)
        j = json.loads(r.stdout or "{}")
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return {}
    s = (j.get("streams") or [{}])[0]
    try:
        duration = float(j.get("format", {}).get("duration") or 0)
    except ValueError:
        duration = 0.0
    return {"codec": s.get("codec_name"), "width": s.get("width", 0), "height": s.get("height", 0),
            "field_order": s.get("field_order"), "duration": duration} if s.get("codec_name") else {}


def test_encoders(cfg, state):
    """Tries each encoder on a second of test picture: ffmpeg lists encoders the hardware can't
    actually run. Results kept in the state until tested again."""
    results = {}
    for codec, encoder, kind in ENCODERS:
        cmd = [cfg["ffmpeg"], "-hide_banner", "-nostdin", "-v", "error"]
        if kind == "vaapi":
            cmd += ["-vaapi_device", cfg["vaapi_device"]]
        cmd += ["-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=1"]
        cmd += video_args(cfg, encoder, kind, "balanced", ["format=yuv420p"] if kind == "cpu" else [])
        cmd += ["-f", "null", "-"]
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
            err = [l for l in r.stderr.strip().splitlines() if l.strip()]
            results[encoder] = "ok" if r.returncode == 0 else (err[-1] if err else f"exit {r.returncode}")
        except (OSError, subprocess.TimeoutExpired) as e:
            results[encoder] = str(e)
    try:
        version = subprocess.run([cfg["ffmpeg"], "-version"], capture_output=True, text=True, timeout=15).stdout.splitlines()[0]
    except (OSError, IndexError, subprocess.TimeoutExpired):
        version = "not found"
    with LOCK:
        state["encoders"] = {"checked": time.time(), "ffmpeg": version, "results": results}
        save_state(state)
    log("Encoders: " + ", ".join(f"{e} {'ok' if v == 'ok' else 'no'}" for e, v in results.items()))
    return results


def pick_encoder(cfg, state):
    """(encoder, kind) for the settings, or (None, why)."""
    results = state.get("encoders", {}).get("results", {})
    wanted = cfg["transcode_encoder"].strip()
    codec = cfg["transcode_codec"].strip().lower()
    for c, encoder, kind in ENCODERS:
        if (wanted == encoder or (wanted == "auto" and c == codec)) and results.get(encoder) == "ok":
            return encoder, kind
    if wanted != "auto":
        return None, f"{wanted} doesn't work here ({results.get(wanted, 'not tested')})"
    return None, f"no {codec} encoder works here"


def in_hours(spec, now=None):
    """Whether it's within "1-6" (from 1:00 to 5:59; "22-6" wraps past midnight); empty: always."""
    m = re.fullmatch(r"\s*(\d{1,2})\s*-\s*(\d{1,2})\s*", spec or "")
    if not m:
        return True
    a, b = int(m.group(1)) % 24, int(m.group(2)) % 24
    h = time.localtime(now).tm_hour
    return a <= h < b if a < b else (h >= a or h < b) if a > b else True


def transcode_candidates(cfg, state):
    """Finished .ts recordings, done with Comskip, that the transcode settings allow: oldest first
    (the newest are the likeliest to be watched)."""
    if not cfg.getboolean("transcode") or not cfg["nextpvr_pin_md5"] or not in_hours(cfg["transcode_hours"]):
        return []
    now = time.time()
    wait = cfg.getfloat("transcode_wait_hours") * 3600
    since = state.get("transcode_started", now)
    backfill = cfg.getboolean("transcode_backfill")
    jobs = state.get("transcode", {})
    out = []
    for ts in recordings(cfg, (".ts",)):
        key = str(ts)
        job = jobs.get(key, {})
        if job.get("state") in ("done", "skipped", "failed", "transcoding", "queued"):
            continue
        if job.get("state") == "retry" and now - job.get("finished", 0) < RETRY_AFTER:
            continue
        try:
            mtime = ts.stat().st_mtime
        except OSError:
            continue
        if now - mtime < max(cfg.getint("settle"), wait) or Path(key + ".map").exists():
            continue
        if not backfill and mtime < since:
            continue
        meta = info(ts)
        if meta is None or meta.get("status", "").upper() != "READY":
            continue
        # Comskip first (it reads the original): marks written, or never going to be.
        scan_state = state["items"].get(key, {}).get("state")
        if not ts.with_suffix(".edl").exists() and not excluded(cfg, meta) and scan_state != "failed":
            continue
        channel = {meta.get("channel", "").lower(), meta.get("channel_OID", "").lower()} - {""}
        if channel & names(cfg["transcode_skip_channels"]) or meta.get("title", "").lower() in names(cfg["transcode_skip_shows"]):
            continue
        out.append((mtime, ts))
    return [ts for _, ts in sorted(out)]


class NextPvrSession:
    """A NextPVR sign-in for switching recordings to their new file, with the PIN's md5 from the
    settings."""

    sid = None

    @classmethod
    def call(cls, cfg, method, **params):
        for attempt in (1, 2):
            if not cls.sid:
                r = NextPvr.get(cfg, "session.initiate", ver="1.0", device="Comskip scanner")
                md5 = hashlib.md5(f":{cfg['nextpvr_pin_md5']}:{r['salt']}".encode()).hexdigest()
                if NextPvr.get(cfg, "session.login", sid=r["sid"], md5=md5).get("stat") != "ok":
                    raise RuntimeError("NextPVR didn't accept the saved PIN: save it again on the page")
                cls.sid = r["sid"]
            try:
                reply = NextPvr.get(cfg, method, sid=cls.sid, **params)
            except urllib.error.HTTPError:
                reply = {"stat": "fail"}
            # A session NextPVR forgot (it restarted): sign in again once.
            if isinstance(reply, dict) and reply.get("stat") == "fail" and attempt == 1:
                cls.sid = None
                continue
            return reply
        return reply


def nextpvr_recording(cfg, path):
    """NextPVR's entry for this file, or None."""
    reply = NextPvrSession.call(cfg, "recording.list", filter="ready")
    for r in reply.get("recordings", []) if isinstance(reply, dict) else []:
        if r.get("file") == str(path):
            return r
    return None


def keep_original(cfg, state, ts, new):
    """Moves the original into .originals in its recording folder (kept transcode_keep_days), or
    deletes it when that's 0."""
    days = cfg.getfloat("transcode_keep_days")
    if days <= 0:
        ts.unlink(missing_ok=True)
        return None
    root = next((Path(d) for d in recording_dirs(cfg) if ts.resolve().is_relative_to(Path(d).resolve())), ts.parent)
    dest = root / ORIGINALS / ts.resolve().relative_to(root.resolve())
    dest.parent.mkdir(parents=True, exist_ok=True)
    # Same drive as the recording, so this is a rename, not a copy.
    os.replace(ts, dest)
    until = time.time() + days * 86400
    with LOCK:
        state.setdefault("originals", []).append({"path": str(dest), "file": str(new), "until": until})
    return until


def tidy_originals(state):
    """Deletes kept originals whose days are up."""
    now = time.time()
    with LOCK:
        kept = state.get("originals", [])
        due = [o for o in kept if o["until"] <= now]
        if not due:
            return
        for o in due:
            Path(o["path"]).unlink(missing_ok=True)
            log(f"Deleted the original of {Path(o['file']).name} (kept {ORIGINALS})")
        state["originals"] = [o for o in kept if o["until"] > now]
        save_state(state)


def transcode(cfg, ts, state):
    """Converts one recording, checks the result, switches NextPVR's entry to it, then keeps or
    deletes the original. Anything off on the way leaves the original as it was."""
    key = str(ts)
    with LOCK:
        jobs = state.setdefault("transcode", {})
        job = jobs.setdefault(key, {})
        job.update(state="transcoding", since=time.time(), attempts=job.get("attempts", 0) + 1, pos=0)
        for k in ("error", "reason"):
            job.pop(k, None)
        save_state(state)

    def finish(result, **fields):
        with LOCK:
            # Listed again if something took it off the list meanwhile.
            if not any(v is job for v in jobs.values()):
                jobs[key] = job
            job.update(state=result, finished=time.time(), **fields)
            if result == "retry" and job["attempts"] >= cfg.getint("max_attempts"):
                job["state"] = "failed"
            save_state(state)

    tmp = None
    try:
        source = probe(cfg, ts)
        if not source:
            return finish("retry", error="ffprobe couldn't read it")
        job.update(codec=source["codec"], height=source["height"], duration=source["duration"], from_bytes=ts.stat().st_size)
        codec = cfg["transcode_codec"].strip().lower()
        max_h = cfg.getint("transcode_max_height")
        if source["codec"] in EFFICIENT.get(codec, set()) and not (max_h and source["height"] > max_h):
            return finish("skipped", reason=f"already {source['codec']}")
        encoder, kind = pick_encoder(cfg, state)
        if not encoder:
            return finish("failed", error=kind)
        entry = nextpvr_recording(cfg, ts)
        if not entry:
            return finish("skipped", reason="not one of NextPVR's recordings")
        container = "mp4" if cfg["transcode_container"].strip().lower() == "mp4" else "mkv"
        new = ts.with_suffix("." + container)
        if new.exists():
            return finish("failed", error=f"{new.name} is already there")
        tmp = ts.with_name(f".{ts.stem}.transcoding.{container}")
        cmd = ffmpeg_cmd(cfg, encoder, kind, ts, tmp, source)
        log(f"Transcoding {ts.name} ({source['codec']} {source['height']}p) with {encoder}")
        t0 = time.time()
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        errors = []
        threading.Thread(target=lambda: errors.extend(p.stderr.read().splitlines()), daemon=True).start()
        for line in p.stdout:
            # -progress: out_time_us=… as it goes.
            if line.startswith("out_time_us=") and line.strip()[12:].isdigit():
                job["pos"] = int(line.strip()[12:]) / 1e6
        code = p.wait()
        if code != 0:
            noise = ("Invalid frame dimensions", "Last message repeated")
            err = [l for l in errors if l.strip() and not any(n in l for n in noise)]
            return finish("retry", error=(err[-1] if err else f"ffmpeg exit {code}"))
        # The result has to be all there before the original goes.
        out = probe(cfg, tmp)
        size = tmp.stat().st_size
        if not out or abs(out["duration"] - source["duration"]) > max(2.0, source["duration"] * 0.005):
            return finish("failed", error=f"the result is {out.get('duration', 0):.0f} s long, the recording {source['duration']:.0f} s")
        if size >= job["from_bytes"]:
            return finish("skipped", reason=f"the result wasn't smaller ({size // 1_000_000} MB)")
        os.replace(tmp, new)
        tmp = None
        reply = NextPvrSession.call(cfg, "recording.rename", **{"from": key, "to": str(new)})
        after = nextpvr_recording(cfg, new)
        if not isinstance(reply, dict) or reply.get("stat") != "ok" or not after or after.get("id") != entry.get("id"):
            new.unlink(missing_ok=True)
            return finish("failed", error=f"NextPVR didn't switch to the new file ({reply})")
        NextPvrSession.call(cfg, "recording.fix.size", recording_id=entry["id"])
        changed = [k for k in ("name", "subtitle", "startTime", "duration", "channel") if entry.get(k) != after.get(k)]
        if changed:
            log(f"Note: NextPVR's {', '.join(changed)} changed for {new.name}")
        until = keep_original(cfg, state, ts, new)
        with LOCK:
            # The marks and this job now belong to the new file.
            if key in state["items"]:
                state["items"][str(new)] = state["items"].pop(key)
            jobs.pop(key, None)
            jobs[str(new)] = job
            job.update(encoder=encoder, to_bytes=new.stat().st_size, took=round(time.time() - t0), kept_until=until)
        log(f"Transcoded {ts.name} in {time.time() - t0:.0f} s: {job['from_bytes'] // 1_000_000} → {job['to_bytes'] // 1_000_000} MB")
        finish("done")
    except Exception as e:  # noqa: BLE001 - one bad recording mustn't stop the scanner
        log(f"Transcoding {ts.name} failed: {e}")
        finish("retry", error=str(e))
    finally:
        if tmp is not None:
            tmp.unlink(missing_ok=True)


# ---- the page --------------------------------------------------------------------------------


class Run:
    """What the main loop and the page share."""

    config_path = None
    cfg = None
    state = None
    # Waiting to be scanned (from the last look), and when the next look is.
    queue = []
    next_look = 0.0
    # Waiting to be transcoded, and ones asked for on the page (they go first, whatever the hours).
    transcode_queue = []
    transcode_asked = []


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
        "transcode": transcode_overview(cfg, state),
        "unlocked": unlocked,
    }


def transcode_overview(cfg, state):
    with LOCK:
        jobs = {k: dict(v) for k, v in state.get("transcode", {}).items()}
        enc = dict(state.get("encoders", {}))
    rows, now = [], None
    for key, v in jobs.items():
        row = describe(Path(key)) | {k: v.get(k) for k in ("state", "reason", "error", "encoder", "codec", "height", "from_bytes", "to_bytes", "took", "kept_until", "since", "finished", "pos", "duration", "attempts")}
        if v.get("state") == "transcoding":
            now = row
        else:
            rows.append(row)
    rows.sort(key=lambda r: r.get("finished") or 0, reverse=True)
    encoder, why = pick_encoder(cfg, state)
    done = [r for r in rows if r["state"] == "done"]
    return {
        "on": cfg.getboolean("transcode"),
        "pin_saved": bool(cfg["nextpvr_pin_md5"]),
        "in_hours": in_hours(cfg["transcode_hours"]),
        "encoder": encoder,
        "encoder_problem": None if encoder else why,
        "encoders": enc,
        "choices": [{"codec": c, "encoder": e, "kind": k} for c, e, k in ENCODERS],
        "now": now,
        "queue": [describe(ts) for ts in Run.transcode_asked + [t for t in Run.transcode_queue if t not in Run.transcode_asked]],
        # Each recording's transcode state, for the Scanned list's buttons.
        "states": {k: {"state": v.get("state"), "pct": round(100 * v["pos"] / v["duration"]) if v.get("pos") and v.get("duration") else None} for k, v in jobs.items()},
        "history": rows,
        "saved": sum((r.get("from_bytes") or 0) - (r.get("to_bytes") or 0) for r in done),
        "originals": len(state.get("originals", [])),
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


def rescan(keys, who=""):
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
            log(f"Rescan asked for {Path(key).name}" + (f" (from {who})" if who else ""))
        save_state(Run.state)
    WAKE.set()
    return n


def recording_file(cfg, key):
    """The recording at this path, if it is one: a recording file inside NextPVR's recording folders."""
    ts = Path(key)
    if ts.suffix.lower() not in VIDEO or ts.name.startswith(".") or ORIGINALS in ts.parts or not ts.is_file():
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

    def who(self):
        """Who asked: the page (a browser) or JDVR, and from which address."""
        agent = self.headers.get("User-Agent", "")
        return f"{'JDVR' if 'okhttp' in agent.lower() or 'jdvr' in agent.lower() else 'the page'} at {self.client_address[0]}"

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
            numbers = ("settle", "nice", "max_attempts", "transcode_max_height", "transcode_keep_days", "transcode_wait_hours")
            problems = [k for k in numbers if k in values and not str(values[k]).isdigit()]
            for k in ("backfill", "transcode", "transcode_backfill"):
                if k in values:
                    values[k] = "true" if values[k] in (True, "true") else "false"
            choices = {
                "transcode_codec": {c for c, _, _ in ENCODERS},
                "transcode_encoder": {"auto"} | {e for _, e, _ in ENCODERS},
                "transcode_quality": {"smaller", "balanced", "better"},
                "transcode_container": {"mkv", "mp4"},
                "transcode_audio": {"copy", "aac"},
            }
            problems += [k for k, ok in choices.items() if k in values and values[k] not in ok]
            if "transcode_hours" in values and str(values["transcode_hours"]).strip() and not re.fullmatch(r"\s*\d{1,2}\s*-\s*\d{1,2}\s*", str(values["transcode_hours"])):
                problems.append("transcode_hours (like 1-6)")
            if problems:
                self.send(400, {"error": "Not allowed: " + ", ".join(problems)})
                return
            with LOCK:
                write_settings(Run.config_path, values)
                Run.cfg = load_config(Run.config_path)
                # Recordings from now on, unless older ones are asked for too.
                if Run.cfg.getboolean("transcode") and "transcode_started" not in Run.state:
                    Run.state["transcode_started"] = time.time()
                    save_state(Run.state)
            log("Settings changed on the page: " + ", ".join(f"{k}={v}" for k, v in values.items()))
            WAKE.set()
            self.send(200, {"saved": list(values)})
        elif path == "/api/ini":
            self.ini(body)
        elif path == "/api/rescan":
            if body.get("all"):
                self.send(200, {"queued": rescan(list(Run.state["items"]), self.who())})
                return
            key = str(body.get("file", ""))
            reason = why_not_rescan(key)
            self.send(200, {"queued": 0 if reason else rescan([key], self.who()), "reason": reason})
        elif path == "/api/clear":
            reason = clear(str(body.get("file", "")))
            if not reason:
                log(f"Marks removal asked by {self.who()}")
            self.send(409 if reason else 200, {"error": reason} if reason else {"cleared": True})
        elif path == "/api/encoders":
            self.send(200, {"results": test_encoders(Run.cfg, Run.state)})
        elif path == "/api/transcode-pin":
            # md5 of the PIN, checked with NextPVR before it's kept (the file becomes private).
            md5 = str(body.get("md5", "")).lower()
            if not re.fullmatch(r"[0-9a-f]{32}", md5):
                self.send(400, {"error": "That isn't a PIN hash."})
                return
            try:
                r = NextPvr.get(Run.cfg, "session.initiate", ver="1.0", device="Comskip scanner")
                ok = NextPvr.get(Run.cfg, "session.login", sid=r["sid"], md5=hashlib.md5(f":{md5}:{r['salt']}".encode()).hexdigest()).get("stat") == "ok"
            except OSError as e:
                self.send(502, {"error": f"NextPVR didn't answer: {e}"})
                return
            if not ok:
                self.send(403, {"error": "NextPVR didn't accept that PIN."})
                return
            with LOCK:
                write_settings(Run.config_path, {"nextpvr_pin_md5": md5})
                os.chmod(Run.config_path, 0o600)
                Run.cfg = load_config(Run.config_path)
                NextPvrSession.sid = None
            log("NextPVR PIN saved for transcoding")
            WAKE.set()
            self.send(200, {"saved": True})
        elif path == "/api/transcode":
            # Transcode one next (whatever the hours and filters): it has to be a finished recording.
            ts = recording_file(Run.cfg, str(body.get("file", "")))
            if ts is None or ts.suffix.lower() != ".ts":
                self.send(400, {"error": "That isn't a .ts recording."})
                return
            with LOCK:
                jobs = Run.state.setdefault("transcode", {})
                if jobs.get(str(ts), {}).get("state") in ("transcoding", "queued"):
                    self.send(200, {"queued": False, "reason": "It's already " + jobs[str(ts)]["state"] + "."})
                    return
                jobs[str(ts)] = {"state": "queued", "asked": time.time()}
                Run.transcode_asked.append(ts)
                save_state(Run.state)
            WAKE.set()
            self.send(200, {"queued": True})
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
    ap.add_argument("--dry-run", action="store_true", help="list what would be scanned and transcoded")
    ap.add_argument("--transcode", metavar="FILE", help="transcode this recording now, then stop")
    ap.add_argument("--test-encoders", action="store_true", help="try each encoder, then stop")
    args = ap.parse_args()
    Run.config_path = Path(args.config)
    Run.cfg = load_config(Run.config_path)
    state = Run.state = load_state()
    # A scan or transcode cut short (stopped or restarted mid-way) goes again.
    for item in list(state["items"].values()) + list(state.get("transcode", {}).values()):
        if item.get("state") in ("scanning", "transcoding"):
            item["state"] = "retry"
            item["finished"] = 0
    if Run.cfg.getboolean("transcode") and "transcode_started" not in state:
        state["transcode_started"] = time.time()
    # Asked for on the page before a restart: still asked for.
    queued = sorted((v.get("asked", 0), k) for k, v in state.get("transcode", {}).items() if v.get("state") == "queued")
    Run.transcode_asked = [Path(k) for _, k in queued if Path(k).exists()]
    save_state(state)
    if args.test_encoders or "encoders" not in state:
        test_encoders(Run.cfg, state)
    if args.test_encoders:
        return
    if args.transcode:
        ts = Path(args.transcode).resolve()
        state.setdefault("transcode", {}).pop(str(ts), None)
        transcode(Run.cfg, ts, state)
        print(json.dumps(state["transcode"].get(str(ts.with_suffix("." + Run.cfg["transcode_container"]))) or state["transcode"].get(str(ts)), indent=1))
        return
    if not args.dry_run and Run.cfg.getint("port"):
        serve(Run.cfg.getint("port"))
    log(f"Watching {', '.join(recording_dirs(Run.cfg)) or 'nothing'} every {Run.cfg['interval']} s")
    while True:
        # Settings changed in the file (or on the page) apply from the next look.
        with LOCK:
            Run.cfg = load_config(Run.config_path)
        cfg = Run.cfg
        tidy(state)
        tidy_originals(state)
        waiting = Run.queue = candidates(cfg, state)
        to_transcode = Run.transcode_queue = transcode_candidates(cfg, state)
        if args.dry_run:
            for ts in waiting:
                print("scan", ts)
            for ts in to_transcode:
                print("transcode", ts)
            return
        if waiting:
            # One at a time, then look again: a newer recording goes first.
            scan(cfg, waiting[0], state)
            continue
        # Transcoding once Comskip is caught up: ones asked for on the page first, in order.
        with LOCK:
            asked = Run.transcode_asked.pop(0) if Run.transcode_asked else None
        if asked or to_transcode:
            transcode(cfg, asked or to_transcode[0], state)
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
