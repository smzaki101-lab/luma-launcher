# Luma Launcher

**A cinematic, tvOS-inspired home screen for Android TV.** Luma brings a Liquid Glass-inspired UI to your TV: a refined app dock, translucent Control Centre, fluid remote focus, App Switcher, live weather, and Continue Watching when real viewing data is available.

[**Download Luma v1.0.13 APK**](https://github.com/smzaki101-lab/luma-launcher/releases/download/v1.0.13/Luma-v1.0.13.apk) · [Release notes](https://github.com/smzaki101-lab/luma-launcher/releases/tag/v1.0.13) · [Installation and recovery](docs/INSTALL.md)

The signed APK includes the remote-friendly keyboard, app search shortcuts, Continue Watching and the latest Now Playing controls. Download it under **Assets** on the release page. Global Menu shortcuts depend on the TV's input and accessibility support; normal launcher operation does not require a companion computer.

![Luma home screen showing the cinematic wallpaper, weather and time widgets, and app dock](screenshots/home.png)

**v1.0.13:** lets you select the album artwork in Control Centre to open Spotify and fixes overlap when the Next control is focused. The v1.0.12 Spotify Continue Listening, local playback history and Continue Watching improvements remain available. See [playback details](docs/LOCAL-PLAYBACK-HISTORY.md).

## Explore Luma

| Control Centre | App Switcher |
| :--- | :--- |
| ![Glass-inspired Control Centre with remote-friendly actions](screenshots/control-centre.png) | ![Recent apps presented as an App Switcher](screenshots/app-switcher.png) |
| A calm, remote-friendly panel for quick actions. An optional shortcut can open it over other apps on compatible TVs. | Move between recently used apps without searching through the full library. |

| Continue Watching |
| :--- |
| ![Continue Watching with title artwork and a cinematic background](screenshots/continue-watching.png) |
| Browse genuine titles from supported Android TV Watch Next entries and Stremio data. Manual entries and optional Trakt support provide additional choices; progress is shown only when a source supplies it. |

Screenshots show one tested setup. Installed apps, content, wallpaper, weather and remote behavior vary by device. The App Switcher screenshot is from an earlier beta. Third-party names, logos and title artwork belong to their owners.

## Highlights

- **Made for a remote:** D-pad navigation, animated focus, an editable dock, All Apps and app launching.
- **Personal home screen:** custom tile identities, wallpapers, live weather with offline cache, clock and screensaver.
- **Easier text entry:** a remote-friendly QWERTY keyboard and [QR phone typing](docs/PHONE-KEYBOARD.md) in launcher fields and standard Android app text fields, with a compact bottom overlay and no phone app required.
- **Liquid Glass-inspired design:** layered translucency, soft highlights and restrained motion inspired by the visual direction of tvOS 26, tuned for Android TV hardware.
- **Quick access:** Control Centre and App Switcher. The optional [global Menu shortcut](docs/GLOBAL-MENU.md) depends on the TV's remote mapping and Accessibility support.
- **Real Continue Watching:** Android TV Watch Next and supported Stremio entries, with provider links where available. Luma never invents playback history or progress.
- **Easy recovery:** Luma does not remove your existing launcher. Android's Home App chooser and [ADB uninstall steps](docs/INSTALL.md) remain available.

## Install

Download the [signed Luma v1.0.13 APK](https://github.com/smzaki101-lab/luma-launcher/releases/download/v1.0.13/Luma-v1.0.13.apk) and follow the [installation and recovery guide](docs/INSTALL.md). Open Luma from your existing app list before choosing any default Home app.

The application ID is `dev.luma.launcher`. Device compatibility beyond tested hardware is still being established; see [known limitations](docs/KNOWN-LIMITATIONS.md). Global Menu handling varies by TV firmware and remote; see the [shortcut guide](docs/GLOBAL-MENU.md).

## Build from source

Install JDK 17 and Android SDK 35, then run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The project uses Gradle 8.9 and can also be opened in Android Studio. Production signing uses local `LUMA_RELEASE_*` environment variables; the private key is not in this repository. See [release preparation](docs/RELEASE-PREPARATION.md), [privacy](docs/PRIVACY.md) and [asset attribution](docs/ASSETS.md).

For PowerShell commands, see [Windows development](docs/WINDOWS-DEVELOPMENT.md). To work on Luma together, see [contributing](CONTRIBUTING.md).

Luma is published under the [Apache License 2.0](LICENSE). “Liquid Glass” and “tvOS 26” describe the design inspiration, not the origin of this app. Luma is independent and is not affiliated with or endorsed by Apple, Google, Stremio or any streaming provider.
