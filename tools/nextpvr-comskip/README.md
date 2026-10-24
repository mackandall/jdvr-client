# Comskip for NextPVR

Finds commercials in finished NextPVR recordings and writes an `.edl` beside each one. NextPVR
serves the `.edl` to clients, and JDVR uses it for the coloured marks on the seek bar, the Skip
prompt and the ✂ badges. NextPVR deletes the `.edl` along with the recording.

> **Already running Comskip from PostProcessing (or ParallelProcessing)? You don't need this.**
> JDVR uses whatever `.edl` sits beside a recording, whoever wrote it. Make sure your
> `comskip.ini` has `output_edl=1`. The scanner leaves recordings that already have an `.edl`
> alone, so the two can run side by side.

## What it adds over a PostProcessing script

- **One scan at a time,** at low CPU priority. Five recordings ending at once don't start five
  Comskips.
- **Retries and catching up.** A failed scan is tried again (3 times by default), and recordings
  made before it was installed are scanned too.
- **A page** at `http://<NextPVR machine>:8867/`: what it's scanning (with time left), what's
  waiting, every result with its breaks and ad time, and the settings. Anyone on your network can
  look. Changing settings or rescanning needs the NextPVR PIN. The page signs in to NextPVR the way
  its own clients do, with a salted hash, so the PIN never leaves the browser and isn't stored.
- **Status in JDVR.** JDVR shows "scanning" and "scan failed" on recordings, read from
  `/api/status` on the same port.
- **No script in NextPVR's `scripts` folder.** NextPVR switches off its own auto transcoding when
  a PostProcessing script exists. This runs beside NextPVR and leaves that alone.

It works from the files alone: the `.xml` NextPVR writes beside each recording says when it's
finished, plus its channel and genres. Scanning needs no NextPVR PIN.

## Install (Linux, on the NextPVR machine)

1. Put `comskip` and `comskip.ini` in this folder. A self-contained Comskip build (x64 and arm64)
   comes with the
   [Comskip Commercial Segments](https://github.com/mackandall/jellyfin-plugin-comskip-segments)
   releases.
2. Run `./install.sh` as an ordinary user. It needs no root. It copies everything to `~/comskip`
   and starts a systemd user service.
3. That user has to be able to read the recordings and write in their folder.

The log is at `journalctl --user -u nextpvr-comskip -f`. To try one pass without the service:
`python3 ~/comskip/nextpvr-comskip.py --once --dry-run`.

## Settings

Set them on the page, or in `~/comskip/nextpvr-comskip.conf` (see `nextpvr-comskip.conf.example`).
All of them are optional:

- Commercial-free channels to skip, or only some channels.
- Genres to skip, for example News or Sports.
- How long a file has to stay unchanged before it's scanned.
- Shows never scanned, for one show on a channel that's otherwise scanned.
- Number of attempts, CPU priority, and the port (`0` turns the page off).

Changes apply from the scanner's next look, with no restart. The exception is the port, which
needs `systemctl --user restart nextpvr-comskip`. A rescan only replaces marks the scanner made
itself, and it leaves alone a recording the settings now exclude.
