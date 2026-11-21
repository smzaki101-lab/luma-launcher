# Luma v1.0.16 — Phone remote improvements

- App-like private phone remote opening on Touchpad.
- Two-finger scrolling alongside scroll buttons.
- Hold-to-repeat arrows and volume, with cancellation on release or connection loss.
- Clear connection health for page and TV input readiness.
- Fixed stale tile-hover highlights when scrolling, including Stremio.
- Phone app picker with icons, typing and optional cross-app pointer highlights.

The signed APK upgrades previous public signed releases in place. Debug-signed development installations require a matching debug build; do not uninstall without backing up settings.

Phone remote setup and cross-app controls require optional local debugging support on compatible devices. Cross-app support varies. See docs/PHONE-REMOTE.md. Existing system launcher remains intact.

Apple TV input compatibility: swipes move native focus, taps select, and OK follows the real press/release duration for show menus. This app does not expose tile bounds, so it uses native focus rather than a free-position pointer. Two-finger scrolling, tapping, swiping and held OK were confirmed on the owner's device. Other apps retain their existing pointer handling.

- Consistent press animations on all phone buttons and a white touchpad ring while in use.
