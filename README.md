# JDVR — a Jellyfin DVR client for Android TV

A TV client for Jellyfin's live TV and DVR: live TV and a guide first, recordings next. It works
with any Jellyfin server (10.11 or 12), and does more with a few optional server plugins (below).

**Experimental:** used every day on one household's server, but young. Expect rough edges, and
please open an issue when something breaks.

## Install

Android TV 6 or newer (Google TV, Shield, Fire TV, onn and other Android TV boxes). JDVR isn't in
an app store: install the APK yourself.

1. On the TV, install the **Downloader** app (from the Play Store or Amazon Appstore) and allow it
   to install apps when it asks (Settings › Apps › Special app access › Install unknown apps).
2. In Downloader, open
   `https://github.com/mackandall/jdvr-client/releases/latest/download/jdvr.apk` and install.
3. Open JDVR: it finds Jellyfin servers on your network, or type the address. Sign in with Quick
   Connect (approve the code in the Jellyfin web client) or a password.

To update, do step 2 again: the same address always gives the latest version, and it installs
over the old one without signing you out. From a computer instead: `adb install -r jdvr.apk`.
Each [release](https://github.com/mackandall/jdvr-client/releases) lists what changed and the
APK's SHA-256.

## Works best with

All optional: without one, JDVR falls back to Jellyfin's own features or says what's missing.
**Settings › Server features** in the app shows which ones your server has. To add one: Jellyfin
dashboard › Plugins › Repositories › add the repository address, then Plugins › Catalog › install,
and restart Jellyfin.

| Plugin | What it adds in JDVR | Repository |
|---|---|---|
| Comskip Commercial Segments | Commercials found and skipped (also while recording), ✂ badges, program start, recording edits | `https://raw.githubusercontent.com/mackandall/jellyfin-plugin-comskip-segments/main/manifest.json` |
| Intro Skipper | Intros, recaps and previews found and skipped | `https://intro-skipper.org/manifest.json` |
| Episode History | New recordings on Home, Recorded Shows by show, watched / in library / recorded marks in the guide, Forget this episode | not published yet |
| JellyDVR Schedule | Tuners in use, "all tuners are busy" before tuning, conflicts and priorities, Recording Favourites | not published yet |
| JellyDVR Live | Pause and rewind live TV past when you tuned in; keep what you're watching | not published yet |
| JellyDVR Space | Move to archive…, keeping the recordings drive from filling up | not published yet |
| XMLTV Guide Cleaner | Cleaner movie titles in the guide (for guides that put the year in the title) | not published yet |

## What's in it

- Kotlin, Compose for TV, Media3, Jellyfin SDK. No DI framework.
- Sign in with Quick Connect (approve the code from the web client) or a password.
- Live TV: channel list with what's on; player with channel up/down, number entry, info bar;
  asks JellyDVR Schedule whether a tuner is free first ("All tuners are busy").
- Guide: 2-hour window, Episode History marks as chips, programme panel with Watch, Record,
  Record series, Record this instead, Recording Favourites.

## Screenshots

Two of the themes: *Ode to Emby's Apple* (light) and *Midnight* (dark).

| | Ode to Emby's Apple | Midnight |
|---|---|---|
| Home | ![Home, Apple theme](screenshots/apple-1-home.jpg) | ![Home, Midnight theme](screenshots/midnight-1-home.jpg) |
| Menu | ![Menu, Apple theme](screenshots/apple-2-menu.jpg) | ![Menu, Midnight theme](screenshots/midnight-2-menu.jpg) |
| Guide | ![Guide, Apple theme](screenshots/apple-3-guide.jpg) | ![Guide, Midnight theme](screenshots/midnight-3-guide.jpg) |
| Recorded Shows | ![Recorded Shows, Apple theme](screenshots/apple-4-recorded-shows.jpg) | ![Recorded Shows, Midnight theme](screenshots/midnight-4-recorded-shows.jpg) |
| A show's recordings | ![Show episodes, Apple theme](screenshots/apple-extra-show-episodes.jpg) | ![Show episodes, Midnight theme](screenshots/midnight-extra-show-episodes.jpg) |
| TV episode details | ![TV details, Apple theme](screenshots/apple-6-tv-details.jpg) | ![TV details, Midnight theme](screenshots/midnight-6-tv-details.jpg) |
| Movies Archive | ![Movies Archive, Apple theme](screenshots/apple-5-movies-archive.jpg) | ![Movies Archive, Midnight theme](screenshots/midnight-5-movies-archive.jpg) |
| Movie details | ![Movie details, Apple theme](screenshots/apple-7-movie-details.jpg) | ![Movie details, Midnight theme](screenshots/midnight-7-movie-details.jpg) |

Playing a recording: commercial breaks (orange) and recap/intro (purple) marked on the seek bar,
with a skip prompt.

![Player with commercial marks](screenshots/apple-8-player-commercial-marks.jpg)

Build: `JAVA_HOME=~/opt/jdk21 ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug`
(installs as `com.mackandall.jdvr.debug`, beside Wholphin).
Release: raise `jdvr.version` in `gradle.properties`, commit and push, then `./release.sh` builds
the signed APK and makes a draft GitHub release with it.

GPL-2.0: `app/src/main/java/com/mackandall/jdvr/profile` comes from Wholphin / Jellyfin Android TV.
