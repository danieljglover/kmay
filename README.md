# KMay

A remote-friendly Android / Fire TV media player with an optional self-hosted media proxy.

[Download the signed APK](https://github.com/danieljglover/kmay/releases/latest/download/kmay.apk) · [Releases](https://github.com/danieljglover/kmay/releases)

Downloader code: **5527034** · Short link: [aftv.news/5527034](https://aftv.news/5527034). This points to the latest APK release.

## Install

Use Downloader on a compatible Android-based Fire TV to open the APK download link, allow Downloader to install apps, and install KMay. Android 6 or later is required. Vega OS does not run this Android APK.

For a self-hosted proxy, open **Settings → Manage sources → Configure server**, enter your server URL including port 8788 and its access token, then select **Test and save**. The device must be able to reach that server on your network. Credentials are not bundled with this release.

## Features

- Films and series browsing, search, collections and continue watching.
- Remote navigation, native HLS/DASH playback, subtitles and playback controls.
- Optional server-side source discovery and daily verification.
- A visible comparison of enabled sources before automatic playback, with up to 20 seconds of active testing per connected source and a 60-second overall deadline, cancellation and early selection.
- Audio and video segment checks, tested HLS variant selection and fallback to alternate working streams.
- Source availability diagnostics distinguish discovered entries from verified playback integrations.

Catalogues do not guarantee a playable stream. Source availability and language coverage vary by title and provider. This app does not bundle media, download torrents or unlock DRM.

## Build

Install JDK 17 and Android SDK 35. Set `ANDROID_HOME` or configure `sdk.dir` in an untracked `local.properties` file.

```sh
./gradlew assembleDebug testDebugUnitTest lint
```

Use `gradlew.bat` on Windows. Debug builds use package `uk.kmay.media.debug`. Release builds use `uk.kmay.media`.

For your own signed release, provide an untracked `.signing/signing.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run `./gradlew assembleRelease`. The published app's private signing key is not included.

## Proxy

See [server/README.md](server/README.md) for deployment. Runtime data, personal network configuration, passwords and private deployment tools are excluded from this repository.

## Verification

The app was exercised in BlueStacks; physical Fire TV hardware has not been tested. Server regression tests cover source selection, HLS audio/later-segment failures, cancellation, relay rewriting and alternate-stream fallback.

```sh
python -m pip install -r server/requirements.txt pytest pytest-asyncio httpx
python -m pytest server/tests -q
```

Android instrumentation tests are in `app/src/androidTest`. The optional live proxy test accepts `proxyUrl` and `proxyToken` instrumentation arguments. Do not publish credentials in test logs or issues.
