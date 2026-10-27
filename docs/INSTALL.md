# Installation and recovery

Download the [signed v1.0.13 APK](https://github.com/smzaki101-lab/luma-launcher/releases/download/v1.0.13/Luma-v1.0.13.apk) and install it using a compatible Android TV installer or ADB:

```sh
adb install Luma-v1.0.13.apk
```

Luma's package ID is `dev.luma.launcher`. Open Luma from your current app list first. You do not need to make it the default Home app to try it. If you later choose Luma as Home, use **Launcher settings → Home app selection** to return to Android's Home App chooser.

**Upgrading from the earlier `v1.0.0-rc1` or another debug-signed build:** those builds use a different signing key, so Android cannot install the signed release over them. Uninstall the debug build before installing the signed release. Uninstalling clears Luma's local preferences and caches, so save anything important first.

To uninstall only Luma:

```sh
adb uninstall dev.luma.launcher
```

If ADB has more than one connected device, add `-s DEVICE_ID` immediately after `adb`. Uninstalling Luma does not remove or disable your device's existing system launcher. Keep a copy of a known-working APK before changing versions.
