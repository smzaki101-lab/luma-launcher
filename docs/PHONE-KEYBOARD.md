# Luma keyboard and QR phone typing

Luma includes a bundled Android keyboard for other apps, plus its shared launcher text editor. No separate keyboard APK, phone app or PC bridge is needed. Both use a compact panel at the bottom so the app stays visible above it.

## Use it

1. Enable **Luma Keyboard** in Android keyboard settings and select it as the current keyboard. It is already enabled and selected on the test projector.
2. Open a standard Android text field, for example Stremio Search. Use the remote's arrows and OK on QWERTY, or scan the QR beside it.
3. Keep your phone and projector on the same local network. Phone text appears directly in the current app field.
4. Tap **Go** on the phone, or its keyboard Go/Enter key. Luma performs the field's Android action, such as Search, Go, Next or Send. The projector button does the same. Close or Back hides the keyboard; text already entered in another app remains in that app.

Luma's own fields still use their existing Save/Cancel behavior. Their QWERTY editor is also a smaller bottom panel. Cancel there discards the draft.

The remote keyboard includes digits, Shift, symbols, Space, Delete, cursor arrows and Clear. Client apps retain their own input filters and limits. Apps that draw their own keyboard rather than requesting Android input may keep that custom interface.

## Universal Menu keyboard

Open the current app's search field, press the universal **Menu** button, and choose **Keyboard**. The same compact QWERTY/QR panel is available in every app. Phone **Go** submits the composed text after returning focus to the app.

For standard Android fields, Luma uses its selected input method and supports Unicode. On devices with a working local debugging connection, custom TV fields can instead receive printable English/ASCII text. This fallback verifies the foreground package before sending text and refuses known password fields. It cannot create a text field in an app or guarantee support for every custom interface. Usually it appends at the cursor and presses Enter, so clear an existing query first. Apple TV's Search screen is handled specially: Luma clears the old query, enters the new text, and does not press Enter, which would select a result. Prime Video uses the direct search link below.

## Prime Video search

Prime Video TV ignores ordinary Android text and letter events in its custom search screen on the tested P6 build. From Prime, choose **Menu → Keyboard**, compose your query, and choose **Go**. Luma opens `https://app.primevideo.com/search?phrase=...` in the installed Prime activity with the query URL-encoded. Prime may ask you to select your existing profile before showing the requested results. This shortcut searches; it does not replace Prime's sign-in or password fields.

P6 verification (1.0.5): submitted `inception` through the overlay's live QR phone Go endpoint; Prime displayed that query and matching results. The local menu helper remained running after installation. Release build, JVM tests and lint passed.

## Saved Wi-Fi password (P6)

In the projector's Wi-Fi password keyboard, new users see **Save Wi-Fi password**. Select it, enter a password with the remote, and choose **Save**. This saves a private reusable value; it does not enter a network password or press Connect. Entry is masked and phone/QR typing is disabled. Use 8–63 printable characters or a 64-digit hexadecimal Wi-Fi key; spaces are preserved.

After saving, choose **Paste Wi-Fi password** to fill the current Wi-Fi box. **Change password** opens an empty replacement draft; Cancel keeps the existing saved value. **Forget password** removes the saved value and brings back Save. This is one reusable password per projector, not a per-network password manager. Nothing is put in Android's global clipboard, bundled in the APK, synced or committed to Git. The button is restricted to the P6 settings app's identified Wi-Fi editor; other password fields are unaffected.

P6 verification (1.0.10): tested an empty saved state, remote entry and Save, Paste into the Wi-Fi editor, Change/Cancel preserving the value, and Forget removing it. No network connection was submitted. The owner's original private setting was restored after testing. Release build, lint and 51 JVM tests passed.

## YouTube and TizenTube search

Their TV interfaces draw their own keyboards and do not request Android's selected IME. While either app is open, press the universal **Menu** button and choose **Keyboard**. Luma opens its compact QWERTY/QR panel over that app. Type with the remote or phone, then press **Go**. Results open in the same app using its existing search link. Cancel/Back discards the draft and returns to the app.

This is a menu shortcut for search, not an automatic replacement of the apps' built-in alphabetic keyboard. It needs no additional Accessibility permission. The P6 bridge passes the foreground package; the existing optional Accessibility menu also provides this action. Only the two known YouTube TV/TizenTube packages are accepted, and the query is URL-encoded into an explicitly targeted search link. Other apps retain the normal launcher Search menu action.
## Phone sessions

Each visible editor has a fresh random connection token and local port. Codes expire after 10 minutes. New QR code replaces the link. Hiding the keyboard, changing fields or apps, closing a Luma editor, or stopping its activity closes the old session.

Phone typing is unavailable for password fields, text over 2048 characters, and editors that do not expose a complete text snapshot through Android. Remote input remains available. Phone input replaces the current field text; it is not a phone-wide remote control.

Phone typing uses temporary local HTTP and no cloud service. Guest Wi-Fi isolation or VPN routing can block it. Use a trusted local network. No text, pairing token or typing history is logged or saved by the keyboard. See [privacy](PRIVACY.md).

## Changing keyboards

Select the original System Keyboard or Android Keyboard in Android keyboard settings to switch back. The original keyboards remain installed and enabled. For development, use `adb shell ime list -a -s` to list them, then `adb shell ime set <id>` to choose one. The previous projector keyboard was `com.android.inputmethod.pinyin/.PinyinIME`.

## Development

- `LumaInputMethodService.kt`: bundled IME, real app InputConnection editing, non-fullscreen bottom panel, D-pad routing, phone-session and password isolation. Content insets preserve the application layout behind the panel.
- `GlassNameDialog.kt`: launcher editor, QR display, Save/Cancel and dialog-owned cleanup.
- `QwertyKeyboardView.kt`: reusable remote keys with direct field or InputConnection actions.
- `PhoneTextSession.kt`: bounded token-protected local server, exact Host/Origin checks, strict UTF-8, expiry, single-use submit and cleanup.
- `assets/phone-keyboard.html`: serialized live typing followed by Go; repeated submit is blocked.
- `res/xml/luma_input_method.xml` and the manifest register the IME with Android. Only Android may bind it using BIND_INPUT_METHOD.
- ZXing Core 3.5.3 generates QR codes locally; its Apache 2.0 license is bundled under assets/licenses.

Verification: 33 JVM tests and 5 targeted projector tests passed. The integration test exercises Android input, D-pad navigation, QR text delivery, phone Go invoking Search, previous-session closure and password classification. End-to-end testing in Stremio confirmed the bottom overlay, QR scan and phone Go returning search results. Release build and lint passed with 0 errors and 52 existing warnings.

Android instrumentation force-stops its target app at completion. Launch Luma once after running device tests before checking the IME from another app; ordinary launcher usage does not involve this test-runner stop.

## Optional cross-app pointer highlights (P6 test build)

The private phone remote offers **Connection & help → Cross-app pointer highlights**. It is off by default; the owner can turn it on for supported controls in other apps. This uses a separate on-device Android UI automation helper through the already configured local debugging connection. It inspects control bounds, never reads node text in Luma's code, and does not save screenshots or screen text. Android still grants the helper broad screen-structure access. Existing accessibility services are not suppressed.

Bounds are cached and scanned on a separate worker; pointer movement and clicks never wait for a scan. Over an exposed control, a light rounded highlight replaces the circle. Apps that do not expose usable bounds keep the circular pointer. This does not resize another app's real views or guarantee every control is detectable. Turning the option off closes the helper. It also disconnects after pointer activity stops and starts again when needed.

Targeted P6 checks: highlights appeared over Stremio posters and YouTube content cards. Matched on/off request timing remained approximately 25–32 ms median; these are command-response measurements, not end-to-end display latency. The feature remains an optional device-dependent enhancement.

## Phone gestures and connection health

Touchpad opens by default. Use one finger to point and tap, or two fingers to scroll; lifting one finger after scrolling cannot accidentally click. Existing scroll buttons remain available. Hold an arrow or volume button to repeat after a short initial pause. Release, change modes, leave the page or lose connection to stop repeating. Commands wait for acknowledgement instead of building an unchecked repeat queue.

The connection label distinguishes **TV controls ready**, **TV controls reconnecting** (page reachable but its input helper is unavailable), and **Offline · reconnecting**. It checks recent input-helper acknowledgements without adding another network request per gesture. Android restarts the existing optional input helper when needed.

OK in the phone Buttons view follows the actual press and release, including long holds. Cancellation releases the key, and the helper has a four-second safety release for a lost phone connection. On the tested P6, OK matches the physical remote device and scan code. Apple TV does not expose individual tile geometry and did not accept the pointer-click path reliably; touchpad swipes therefore move Apple TV's native focus and taps select that focused title. Two-finger scrolling and scroll buttons move its native rows. Other apps retain ordinary pointer and browser touch-click behaviour. Apple TV mode is identified locally from the foreground task; no credentials or content history are read.
