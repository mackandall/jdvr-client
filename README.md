# JDVR — a Jellyfin DVR client for Android TV

A TV client built around the JellyDVR server plugins (Schedule, Live, Episode History, Comskip):
live TV and a guide first, recordings next.

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

GPL-2.0: `app/src/main/java/com/mackandall/jdvr/profile` comes from Wholphin / Jellyfin Android TV.
