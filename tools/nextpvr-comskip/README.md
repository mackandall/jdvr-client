# Comskip for NextPVR

Finds commercials in finished NextPVR recordings and writes an `.edl` beside each one, and can
transcode recordings to a smaller format afterwards. NextPVR serves the `.edl` to clients, and
JDVR uses it for the coloured marks on the seek bar, the Skip prompt and the ✂ badges. NextPVR
deletes the `.edl` along with the recording.

> **Already running Comskip from PostProcessing (or ParallelProcessing)? You don't need this for
> the marks.** JDVR uses whatever `.edl` sits beside a recording, whoever wrote it. Make sure your
> `comskip.ini` has `output_edl=1`. The scanner leaves recordings that already have an `.edl`
> alone, so the two can run side by side, for example if you only want its transcoding.

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
- **Scan and remove from JDVR.** A recording's page in JDVR has "Scan for commercials" or
  "Remove commercials". Removing leaves an empty `.edl`, so the scanner won't scan it again on its
  own. JDVR signs in with the PIN it already has. It only offers these for recordings whose marks
  the scanner made. Marks from your own Comskip run are left alone.
- **No script in NextPVR's `scripts` folder.** NextPVR switches off its own auto transcoding when
  a PostProcessing script exists. This runs beside NextPVR and leaves that alone.

## Transcoding (optional, off by default)

Once a recording has its marks, the scanner can convert it to a smaller format, HEVC by default,
and switch NextPVR's recording to the new file. It's the same recording in NextPVR: title, times,
channel, series, watched and resume point stay, and the `.edl` marks keep working. A 2 GB
MPEG-2 recording comes out around 450 MB in about 2 minutes on an Intel graphics chip.

- **One at a time, after Comskip,** oldest first (the newest are the likeliest to be watched).
- **Checked before anything changes.** The new file is written under a hidden name, its length
  compared with the recording's, and it has to be smaller. Only then is NextPVR switched to it.
  If anything goes wrong, the original stays as it was.
- **The original is kept** in `.originals` inside the recording folder for 2 days (you choose,
  or delete it straight away), then deleted.
- **Encoders are tested on the machine.** The page lists which work (graphics chip or CPU), and
  Automatic uses the first that does. Recordings already in HEVC or AV1 are left alone.
- **Settings on the page:** format, encoder, quality, picture size, MKV or MP4, sound as recorded
  or AAC, how long to keep originals, how long after a recording to wait, which hours, older
  recordings too, and channels or shows to leave alone. **Transcode** in the Scanned list does one
  straight away.

It needs only NextPVR, ffmpeg (which NextPVR on Linux already uses) and the scanner. Switching
NextPVR to the new file needs a NextPVR sign-in, so the page asks for the PIN once and keeps its
hash in `nextpvr-comskip.conf`, readable only by the scanner's user. For the graphics chip, that
user needs to be in the `render` group.

Broadcast captions don't survive transcoding.

Scanning works from the files alone: the `.xml` NextPVR writes beside each recording says when
it's finished, plus its channel and genres. Scanning needs no NextPVR PIN.

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

The page's **Comskip** section shows which Comskip program and ini are in use, and the version.
There you can view the ini, upload another one, switch between the inis in `~/comskip`, or put
the previous one back. An upload is added beside the others and never replaces one. The page warns
if an ini lacks `output_edl=1`, because without it there are no marks. The program itself can only
be changed on the machine (replace `~/comskip/comskip`, or set `comskip =` in the `.conf`), so the
PIN can't be used to run something else there.

Changes apply from the scanner's next look, with no restart. The exception is the port, which
needs `systemctl --user restart nextpvr-comskip`. A rescan only replaces marks the scanner made
itself, and it leaves alone a recording the settings now exclude.
