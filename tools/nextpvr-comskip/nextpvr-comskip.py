#!/usr/bin/env python3
"""
Comskip for NextPVR recordings: finds finished recordings without commercial marks and scans
them, one at a time, writing the .edl beside the recording (NextPVR's recording.edl serves it,
and NextPVR deletes it with the recording).

Works from the files alone: the .xml NextPVR writes beside each recording says whether it's
finished (status READY), its channel and genres; no NextPVR sign-in is needed. Settings are in
nextpvr-comskip.conf beside this script (all optional; see DEFAULTS).

Run as a service (systemd user unit, see install.sh), or once by hand: --once [--dry-run].

While it runs it answers on http://<this machine>:<port>/api/status (JDVR's "scanning" and "scan
failed" badges): {"items": {"<recording file>": {"state": "scanning|found|none|retry|failed", ...}}}.
"""

import argparse
import configparser
import json
import os
import subprocess
import sys
import threading
import time
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
    # Recordings that finished before the scanner first ran are scanned too (oldest last).
    "backfill": "true",
    # Attempts before a recording is given up on (shown as failed).
    "max_attempts": "3",
    # CPU priority for Comskip (19 = lowest).
    "nice": "15",
    # Port for the status page (JDVR's badges); 0: none.
    "port": "8867",
}

STATE = HERE / "nextpvr-comskip-state.json"


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
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=1))
    tmp.replace(STATE)


def candidates(cfg, state):
    """Finished recordings without an .edl that the settings allow, newest first."""
    skip_ch, only_ch, skip_genre = names(cfg["skip_channels"]), names(cfg["only_channels"]), names(cfg["skip_genres"])
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
            if meta:
                channel = {meta.get("channel", "").lower(), meta.get("channel_OID", "").lower()} - {""}
                if channel & skip_ch:
                    continue
                if only_ch and not channel & only_ch:
                    continue
                if {g.lower() for g in meta["genres"]} & skip_genre:
                    continue
            out.append((mtime, ts))
    return [ts for _, ts in sorted(out, reverse=True)]


def scan(cfg, ts, state):
    key = str(ts)
    item = state["items"].setdefault(key, {})
    item["attempts"] = item.get("attempts", 0) + 1
    item["state"] = "scanning"
    item["since"] = time.time()
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
    if code in (0, 1):
        if not edl.exists():
            edl.write_text("")
        breaks = [l for l in edl.read_text().splitlines() if l.strip()]
        item.update(state="found" if breaks else "none", breaks=len(breaks), finished=time.time())
        log(f"Done in {time.time() - t0:.0f} s: {len(breaks)} break(s) in {ts.name}")
    else:
        failed = item["attempts"] >= cfg.getint("max_attempts")
        item.update(state="failed" if failed else "retry", error=" ".join(err) or f"exit {code}", finished=time.time())
        log(f"Comskip failed on {ts.name} (exit {code}{': ' + ' '.join(err) if err else ''})" + ("; giving up" if failed else "; will retry"))
    save_state(state)


def tidy(state):
    """Forget recordings that are gone (deleted in NextPVR)."""
    gone = [k for k in state["items"] if not Path(k).exists()]
    for k in gone:
        del state["items"][k]
    if gone:
        save_state(state)


class Status(BaseHTTPRequestHandler):
    """GET /api/status: what's been scanned, from the state file (written whole, so always complete)."""

    def do_GET(self):
        if self.path.split("?")[0] != "/api/status":
            self.send_error(404)
            return
        try:
            body = STATE.read_bytes()
        except OSError:
            body = b'{"items": {}}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def serve(port):
    try:
        server = ThreadingHTTPServer(("", port), Status)
    except OSError as e:
        log(f"No status page on port {port}: {e}")
        return
    threading.Thread(target=server.serve_forever, name="status", daemon=True).start()
    log(f"Status on port {port}: /api/status")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", default=str(HERE / "nextpvr-comskip.conf"))
    ap.add_argument("--once", action="store_true", help="scan what's waiting, then stop")
    ap.add_argument("--dry-run", action="store_true", help="list what would be scanned")
    args = ap.parse_args()
    cfg = load_config(Path(args.config))
    state = load_state()
    # A scan cut short (stopped or restarted mid-scan) goes again.
    for item in state["items"].values():
        if item.get("state") == "scanning":
            item["state"] = "retry"
    save_state(state)
    if not args.dry_run and cfg.getint("port"):
        serve(cfg.getint("port"))
    dirs = recording_dirs(cfg)
    log(f"Watching {', '.join(dirs) or 'nothing'} every {cfg['interval']} s")
    while True:
        tidy(state)
        waiting = candidates(cfg, state)
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
        time.sleep(cfg.getint("interval"))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
