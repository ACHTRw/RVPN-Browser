# RVPN Browser — Android front-end

Java-only Android application (single Gradle module `app`) that embeds the
`rvpn-core` engine and browses the web through it. The engine speaks the frozen
JSON protocol of [`../docs/PROTOCOL.md`](../docs/PROTOCOL.md); this app is the
Android counterpart of the desktop extension in `../browser/extension/`.

## Build

```bash
./build.sh                 # copy the engine AAR, then assembleDebug + assembleRelease
./build.sh --icons         # regenerate the launcher icons first
./build.sh clean assembleDebug   # any extra argument goes to Gradle
```

`build.sh` takes care of everything the build needs:

1. picks a JDK (`JAVA_HOME`, or the first OpenJDK 17/21 it finds) and refuses to
   continue on JDK < 17 (AGP 8.7 / Gradle 8.9 requirement);
2. picks the Android SDK (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `~/Android/Sdk`,
   `~/android-sdk`, `/opt/android-sdk`, `/usr/lib/android-sdk`) and writes
   `local.properties`;
3. copies `../core/build/rvpncore.aar` to `app/libs/rvpncore.aar` and fails with
   instructions when neither exists;
4. runs `./gradlew assembleDebug assembleRelease` and prints the APK paths.

Requirements: JDK 17+, Android SDK with `platforms;android-35` and
`build-tools;35.0.0` (plus `platform-tools`), and the gomobile AAR:

```bash
cd ../core
gomobile bind -target=android/arm64,android/arm,android/amd64 -androidapi 26 \
    -javapkg=org.rvpnbrowser.core -o build/rvpncore.aar ./mobile/rvpncore
```

Release signing is optional; without a keystore `assembleRelease` produces an
unsigned APK. Export `RVPN_KEYSTORE`, `RVPN_KEYSTORE_PASSWORD`, `RVPN_KEY_ALIAS`
and `RVPN_KEY_PASSWORD` (or pass `-PRELEASE_STORE_FILE=…`) to sign it.

## Layout

```
settings.gradle, build.gradle, gradle.properties   Gradle setup (AGP 8.7.3, Gradle 8.9)
build.sh                                           one-command build
tools/gen_icons.sh                                 launcher icons from the master vector
tools/check_strings.py                             locale/reference consistency checker
app/build.gradle, app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/assets/home.html                      start page (status card + ZJU quick links)
app/src/main/java/com/rvpnbrowser/browser/        22 Java sources
app/src/main/res/                                  layouts, menus, drawables, mipmaps, values, values-en
```

### Java sources

| File | Responsibility |
|---|---|
| `RvpnApp` | `Application`: notification channels, WebView debugging in debug builds |
| `MainActivity` | browser window: address bar, tab strip, page container, menu, mode switching |
| `TabManager`, `BrowserTab` | up to 12 WebViews, clients, session persistence, file chooser, SSL/JS dialogs |
| `RvpnService` | foreground service **in the `:rvpn` process**: starts the engine, publishes `control.json`, notifications, `Shutdown()` |
| `RvpnClient` | control-protocol client: token handshake, reader thread, request timeouts, reconnect with backoff, 500 ms/2 s status polling, event fan-out |
| `BrowserProxy` | `androidx.webkit.ProxyController` wrapper (SOCKS5 first, HTTP fallback, clear on normal mode) |
| `RvpnSettingsActivity`, `ChallengeDialogs`, `ClickCodeView` | settings screen, captcha/second-factor dialogs, tap-to-select captcha view |
| `Downloads` | `HttpURLConnection` + explicit `java.net.Proxy` downloads into `Download/` via MediaStore |
| `RvpnPrefs`, `SiteStore`, `LogStore`, `Json`, `Ui`, `Notifications` | settings, bookmarks/history, log buffer, helpers |
| `RvpnState`, `RvpnEvent`, `RvpnChallenge`, `RvpnAuthInfo` | typed views of the protocol payloads |
| `BootReceiver` | restores RVPN mode after a reboot/app update, falls back to a notification |

## How the engine is wired

* The engine runs in its own process (`android:process=":rvpn"`), so a crash or
  an OOM inside the Go runtime can never take the browser down.
* `RvpnService` calls `StartControlServer()` and publishes
  `{"addr":"127.0.0.1:PORT","token":"…"}` to `<filesDir>/rvpn/control.json`
  (atomic write via rename, mode `0600`). If the engine cannot be loaded at all,
  the reason is published in the same file as `{"error":"…"}` and shown in the UI.
* The UI process reads that file, opens a socket to the core, sends
  `{"token":"…"}` as the first line (exactly as `ServeTCP` expects), and then
  speaks one JSON request per line.
* `start` is sent with `clientDataFile` pointing at
  `<filesDir>/rvpn/client_data.json` so the aTrust session is persisted inside
  the private app storage.
* Both local proxies are always enabled (`socksBind` and `httpBind` are
  `127.0.0.1:0` unless the user pins a port), because RVPN mode prefers SOCKS5
  and needs the HTTP proxy for downloads and as a fallback.

## Modes

* **普通模式 / normal** – `ProxyController.clearProxyOverride()`, no proxy is ever
  left behind (also on every fallback and on `stop`).
* **RVPN 模式 / RVPN** – the engine is started, and once the core reports
  `connected` all WebViews are pointed at `socks5://127.0.0.1:port`, with
  automatic fallback to the HTTP proxy when the WebView rejects the SOCKS rule.
  A 90 s watchdog returns to normal mode (with an explanation) if the connection
  cannot be established.
* Mode and credentials live in `SharedPreferences`; the password is only stored
  there while "记住密码 / Remember password" is checked, otherwise it is kept in
  memory for the current run.

## Deliberate decisions and limitations

* **Cleartext HTTP.** `res/xml/network_security_config.xml` sets
  `cleartextTrafficPermitted="true"` for the base configuration. A browser must
  be able to open `http://` pages (a large part of the ZJU intranet is plain
  HTTP), and WebView consults the network security policy for every navigation.
  The manifest attribute `android:usesCleartextTraffic` is **not** used. User
  installed CAs are trusted for `127.0.0.1`/`localhost` only. Flip the base
  value to `false` if a stricter policy is wanted.
* **Foreground service type** is `dataSync` (the permission set requested by the
  project). Android 15 limits `dataSync` services to ~6 hours per day; a
  `specialUse` type with the corresponding Play declaration would be needed for
  a permanently running tunnel.
* **SOCKS rejection detection.** `androidx.webkit` exposes no way to read the
  proxy override back, so "applied" means: the feature is supported, the call
  did not throw and the listener ran. If a device silently ignores the SOCKS
  rule, the app cannot detect it; the HTTP fallback then never triggers.
* **No Chromium fork.** The app renders with the system WebView and drives it
  through `androidx.webkit`, as specified; the Chromium-side patch sources live
  in `chromium-integration/` and are outside this Gradle build.
* **Not implemented**: full screen video (`onShowCustomView`), page translation,
  incognito tabs, download resumption, `<monochrome>` themed icons.
* `values/` is Simplified Chinese (default) and `values-en/` is the English
  translation; both files must keep an identical key set
  (`python3 tools/check_strings.py`).
