# Grab 2.1 — video and audio downloader for Android

Original app. No ads, no analytics, no accounts. Grab only talks to the sites you visit or download
from, and to GitHub once a day to update its download engine.

The download engine is `yt-dlp` with `ffmpeg`, bundled through
[youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0). The ad blocklist is the
[StevenBlack unified hosts list](https://github.com/StevenBlack/hosts) (MIT). The built-in player uses
AndroidX Media3 (Apache-2.0).

## What it does

- **Browser tab**
  - Home page with a search box, your shortcut tiles and recently visited pages.
  - Round Download button: grey until Grab finds a video on the page, then red (colour is a setting),
    with a pulse and a count. It still works while grey. Drag it anywhere; it stays put.
  - Ad and tracker blocking (about 77,000 hosts), pop-up and redirect blocking with an "Open" prompt,
    and a shield in the address bar to switch blocking off for one site. YouTube adverts are skipped
    inside the player.
  - Menu (⋮): back, forward, add to / remove from home page, history, private mode, remove page overlay,
    copy page report.
  - Private mode: its own separate cookies and logins, no history, wiped when you leave it.
- **Downloading**
  - The page you are on is sent to the engine. If the engine does not recognise the site, Grab offers
    the stream the page itself was playing. DRM-protected video (iPlayer, Netflix, Sky and similar)
    cannot be saved.
  - Quality asked every time (or always best, a setting) on a grid of tiles: each resolution the site
    offers, or audio only, with the file size where the site states it. Your last choice is highlighted.
    Rename before downloading. Video is always MP4.
  - Per-download options: save privately, download just a section, start at a set time.
  - Subtitles saved inside the MP4 when the site has them. Cover art and tags on audio files.
  - A link with several videos gives a tick list. Each file is named after its own video.
  - Logins: tried without first; only if that fails does Grab retry with its browser's cookies for the site.
  - Share → Grab from any app, `+` to paste, and an offer to download a link you copied elsewhere.
- **Downloads tab**: up to three at once (a setting), pause and resume, Wi-Fi only (a setting), retry
  that carries on from the partial file. The queue is saved and survives the app being closed.
- **Files tab**: rename or delete files. Gallery (Movies/Grab and Music/Grab) and Private (kept inside Grab, hidden from Gallery
  and other apps; deleted if Grab is uninstalled). Built-in player: speed and subtitle controls, swipe to
  seek, double-tap to jump 10 seconds, carries on where you stopped.
- **Settings tab**: everything above that is marked as a setting, plus theme, start page, button colour
  and position, engine update, clear history, clear browser data.

## Build

1. Open the folder in Android Studio (Ladybug or newer) and let Gradle sync.
2. For a signed release, put `grab-release.jks` in this folder next to `keystore.properties`, then run
   **Build → Generate Signed App Bundle / APK**, or `./gradlew assembleRelease`
   (output: `app/build/outputs/apk/release/app-release.apk`).
3. Use the same keystore every time. Android only installs an update over the top if the signature matches.

Raise `versionCode` in `app/build.gradle.kts` for each new build. `./gradlew testDebugUnitTest` runs the
off-device tests.

## Notes

- Only `arm64-v8a` is built (Galaxy S23). Add `"armeabi-v7a"` to `abiFilters` for 32-bit phones.
- The engine version shipped inside the library is old; Grab replaces it with the newest yt-dlp on first
  launch and then checks once a day.
- To refresh the blocklist, replace `app/src/main/assets/adhosts.txt`: first line is the number of
  hosts, then one host per line.
- Licence: Grab is released under the GNU General Public License, version 3 (see `LICENSE`), as the
  engine wrapper and the ffmpeg build it includes are. Anyone passing the APK on has to offer this source with it.
- Website, release notes and the latest APK: https://customgpter.github.io/grab/

## Layout

```
app/src/main/java/uk/jj/grab/
  App.kt              start-up: settings, saved queue, blocklist, network watch, engine unpack and update
  MainActivity.kt     tabs, browser menu, private mode, share-sheet and copied-link handling
  Browser.kt          the web view: blocking, pop-ups, fullscreen, video detection, page scripts
  BrowserScreen.kt    address bar and page area
  GrabButton.kt       the round, draggable Download button
  HomeScreen.kt       home page: search, shortcut tiles, recent pages
  Places.kt           bookmarks and history
  Sniffer.kt          notes the media streams a page requests
  AdBlock.kt          host blocklist and per-site switch
  Cookies.kt          temporary cookie hand-off to the engine (retry only)
  Yt.kt               engine commands, lookups, quality list, updater, error wording
  DownloadSheet.kt    lookup flow, quality sheet, per-download options, multi-video tick list
  DownloadStore.kt    saved queue and history
  DownloadService.kt  runs the queue; pause, Wi-Fi only, scheduled starts
  Scheduler.kt        alarm for "start at a set time"
  Library.kt          Gallery and private-folder storage
  DownloadsScreen.kt  queue UI
  FilesScreen.kt      saved files
  PlayerActivity.kt   built-in player
  SettingsScreen.kt   settings
  Prefs.kt, Net.kt, Util.kt   settings store, Wi-Fi watch, helpers
```
