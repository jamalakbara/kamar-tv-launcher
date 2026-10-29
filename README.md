# Kamar — Apple TV-style launcher for the TCL Android TV

Home screen app for the TCL Smart TV (C06_2K_GB, Android TV 11) at `192.168.18.9`.
Kotlin + Jetpack Compose, black Apple TV app look, no ads, no tracking.

Home is laid out like [Arc Launcher](https://github.com/meddouribadis/arclauncher):

- **Sidebar** — profile, Home, Apps, Live TV and HDMI inputs, Settings. Collapses to an icon rail
  while you browse and opens when you move Left into it. Hold OK on any sidebar item to pin it
  open (the page makes room for it); hold OK again to go back to auto-hide.
- **Status bar** — network icon (opens Wi-Fi settings) and a big clock with the date.
  Slides away while you are in the app grid.
- **Watch Next** — what you were last watching (from media sessions), with progress.
- **Dock** — frosted tray with your first six apps; the rest sit in a grid below.
  Focused tiles get a white-and-dark double border that shows on any wallpaper.
- **Wallpaper** (sidebar → Wallpaper) — Reddit or Unsplash slideshow like Arc Launcher, every
  5 min to 24 h, with the photo credit under the clock. Off: your pushed picture, otherwise the
  focused title or app's artwork.
- **Screensaver** — after 5 min without a key press: black screen with a dim clock that moves every 30 s.
- **Apps page** — grid of every app. Hold OK (or press Menu) on any app to move, hide or uninstall it.
- **Remote** — Back jumps to the sidebar, Back again to the dock. Home always returns to the dock.
- **AirPlay** — the TV shows up as **Kamar TV** under Screen Mirroring on iPhone, iPad and Mac.

## AirPlay

Built-in AirPlay screen-mirroring receiver (`app/src/main/cpp`): the RPiPlay-style RAOP server from
[KqSMea8/AirplayServer](https://github.com/KqSMea8/AirplayServer), with its `/info` and SETUP handling
updated after [UxPlay](https://github.com/FDH2/UxPlay), and the FDK AAC decoder for mirrored audio.
It runs as a foreground service, publishes `_airplay._tcp` / `_raop._tcp` on port 7000, and opens a
full-screen player when a phone starts mirroring. Back closes the player.

The player has to open over other apps, which needs the overlay permission once:

```bash
adb -s 192.168.18.9:5555 shell appops set com.tvkamar.launcher SYSTEM_ALERT_WINDOW allow
```

Debug with `adb logcat -s raop AirPlay`. Screen mirroring only: audio-only AirPlay (Music app
speaker picker) and video casting (sending a YouTube or Photos video URL) are not supported yet.

## Build and install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease
adb connect 192.168.18.9:5555
adb -s 192.168.18.9:5555 install -r app/build/outputs/apk/release/app-release.apk
```

## Recently Used row

Needs usage access, granted once over adb:

```bash
adb -s 192.168.18.9:5555 shell appops set com.tvkamar.launcher GET_USAGE_STATS allow
```

## Profile name

Select the name at the top of the sidebar to pick the Google account. Google doesn't give
launchers your real name, so set the display name over adb:

```bash
adb -s 192.168.18.9:5555 shell "am start -n com.tvkamar.launcher/.MainActivity --es profile_name 'Chairil Akbar'"
```

Profile picture: push a square image, then press Home. The current one is rendered from
`profile/sticker.svg` (2× size, cropped to the faces, shrunk to 128 px):

```bash
rsvg-convert -w 3584 -h 3584 profile/sticker.svg -o /tmp/full.png
sips -c 1760 1760 --cropOffset 580 960 /tmp/full.png --out /tmp/crop.png
sips -z 128 128 /tmp/crop.png --out profile/avatar.png
```


```bash
adb -s 192.168.18.9:5555 push profile/avatar.png /sdcard/Android/data/com.tvkamar.launcher/files/avatar.png
```

## Wallpaper

Open **Wallpaper** in the sidebar to pick the source:

- **Reddit slideshow** — subreddits (r/wallpapers, r/EarthPorn, r/spaceporn, ...) and order
  (Hot, Top today/week/month/all, New). Uses Reddit's public RSS feeds, no account needed;
  portrait pictures are skipped.
- **Unsplash slideshow** — separate day (6:00–19:00) and night categories. Needs a free access key from
  [unsplash.com/developers](https://unsplash.com/developers): type it in the panel, or send it over adb:

  ```bash
  adb -s 192.168.18.9:5555 shell "am start -n com.tvkamar.launcher/.MainActivity --es unsplash_key 'YOUR_ACCESS_KEY'"
  ```

Only the current and previous pictures are kept on the TV, so the last one still shows offline.

With the slideshow off, push any picture (jpg, png or webp), then press Home. Delete it to go back to the artwork backdrop.

```bash
adb -s 192.168.18.9:5555 push wallpaper.jpg /sdcard/Android/data/com.tvkamar.launcher/files/wallpaper.jpg
```

## Make it the default home screen

The Google home screen is disabled so the Home button opens Kamar:

```bash
adb -s 192.168.18.9:5555 shell pm disable-user --user 0 com.google.android.tvlauncher
```

Switch back to Google's home screen:

```bash
adb -s 192.168.18.9:5555 shell pm enable com.google.android.tvlauncher
adb -s 192.168.18.9:5555 shell pm uninstall com.tvkamar.launcher
```
