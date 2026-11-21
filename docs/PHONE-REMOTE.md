# Optional Luma phone remote

Luma can serve a private phone remote page on your TV's local Wi-Fi. It is built into Luma: no separate phone app or cloud account is needed. The page offers D-pad, OK, Back, Luma Home and Apps, a touchpad with a visible pointer, scroll controls, volume, mute, the TV's Settings key and a Power key. When Luma Keyboard is active in a standard text field, the same page can type into that field.

## Set up on your own TV

1. Install a build containing this feature and open Luma. Keep your existing launcher installed.
2. Ensure the TV and phone are on the same trusted Wi-Fi. The TV must have its own working local wireless debugging connection for Luma's fast input bridge. This is device dependent; a Mac or other computer is not needed after the connection is set up on a supported TV. Without that connection, the page may load but remote controls will report unavailable.
3. In Luma, open **Launcher settings → Phone remote** and turn it **On**. If you want the pointer over other apps, also grant Luma **Display over other apps** in Android settings. The service is optional and starts again after Luma opens or the device restarts.
4. Select **Show private QR link**. Scan it with your phone and save the page to your phone's Home Screen. The page reconnects automatically when the TV is on the same Wi-Fi. If the TV's IP changes, scan a new QR code.
5. To type in another app, select **Luma Keyboard** as Android's current keyboard, focus a standard text field, then type on the saved phone page. Password fields and apps with their own custom keyboard are excluded. The selected keyboard is an Android user setting; Luma does not silently replace it.

**Keep the QR link private.** It contains a random control token. The page uses local HTTP, so use a trusted network rather than public Wi-Fi. The token is generated and stored on the TV, never committed to Git. Turning Phone remote Off removes its saved token and stops the service.

The Power control sends Android's Power key after a confirmation. On the tested projector this means standby; use the physical remote to wake it. It is not a guaranteed full shutdown or remote wake feature. The Settings control sends Android's Settings key, so it opens the same menu as the physical Settings button where the firmware supports that key.

## For a second P6 or another Android TV

Ask your coding assistant: **“Add the remote app to my P6 using Luma's phone remote guide.”** Point it to this repository and have it check *your TV's own IP*, installed Luma version, signing identity, local debugging support and overlay permission before installing. Do not copy another owner's IP address, private QR link, token, keystore or APK. A build signed for someone else's installation may not update yours.

Source entry points: `PhoneRemoteService.kt` (persistent local page), `PhoneTextSession.kt` (token-protected requests), `PhoneRemoteControl.kt` and `FastPhoneInput.kt` (actions), `PhoneInputBridge.kt` (Android input injection), `PhoneMouseCursor.kt` (pointer), and `assets/phone-keyboard.html` (phone interface). The QR uses the device's current local IP and fixed port 19767. The service is off until enabled in Luma settings.

## Limits and recovery

- The local input bridge needs working wireless debugging on the TV. Firmware can disable it after a reboot or standby. Ordinary Luma launcher use remains independent of the phone remote.
- The pointer needs Android's overlay permission. D-pad and text entry can still work without it when supported.
- A QR link stops working if the TV changes IP or Phone remote is turned off. Scan again after reconnecting the TV.
- To remove Luma, follow the [installation and recovery guide](INSTALL.md). This feature does not change the Android default Home app or remove the original launcher.

## v1.0.16 controls

Touchpad opens by default. One finger points and taps; two fingers scroll. Existing scroll buttons remain. Hold arrows and volume to repeat; release, leave the page or lose connection to stop. Status distinguishes TV controls ready, controls reconnecting and offline. Scrolling clears native tile hover, Luma highlights and magnetic targeting; movement restores hover.

Cross-app pointer highlights are optional, off by default and device-dependent. The on-device debugging helper has broad screen-structure access; Luma uses only control bounds and does not save screen text or screenshots. Unsupported controls retain the circle. Keep the phone link private on a trusted local network.

Apple TV input compatibility: swipes move native focus, taps select, and OK follows the real press/release duration for show menus. This app does not expose tile bounds, so it uses native focus rather than a free-position pointer. Two-finger scrolling, tapping, swiping and held OK were confirmed on the owner's device. Other apps retain their existing pointer handling.
