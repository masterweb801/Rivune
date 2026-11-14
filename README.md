# RIVUNE for Android

**Music Without Boundaries.**

RIVUNE for Android is the companion app for discovering a RIVUNE Server
on the local network and opening its web-based music player inside the
app.

-   **Application ID:** `com.logicrealm.rivune`
-   **Platform:** Android
-   **Implementation:** Java, XML layouts, Android WebView
-   **Discovery:** UDP broadcast on port `39876`

## Features

-   Searches the local network for RIVUNE Server instances.
-   Uses UDP broadcast discovery rather than requiring the user to type
    an IP address.
-   Checks discovered servers through the HTTP health endpoint before
    connecting.
-   Displays the selected server's web player inside an Android WebView.
-   Provides a retry path when no server is found.

## How it works

1.  The app sends the discovery request `MOBS_JUKEBOX_DISCOVER_V1` over
    UDP.
2.  A RIVUNE Server listening on UDP port `39876` replies with JSON
    containing its service identifier and HTTP port.
3.  The app reads the server IP address from the UDP packet's sender
    address.
4.  The app checks the server at `/api/health`.
5.  Once verified, the app opens the server's web interface in its
    WebView.

The discovery response uses the service identifier `mobs-jukebox`. Keep
these protocol values in sync with the server project when making
changes.

## Requirements

### To use the app

-   An Android device.
-   A running RIVUNE Server.
-   Both devices connected to the same Wi-Fi/LAN.
-   Local-network communication permitted by the router and device
    firewall.

### To build the app

The project is intended to be buildable from Termux with the Android SDK
and Gradle installed. The development setup used for this project
includes:

-   Java 17 for the Gradle daemon.
-   Gradle `9.9.1`.
-   Android Gradle Plugin `9.4.0`.
-   Android compile SDK `35`.

Your installed Android SDK and Gradle environment may need compatible
versions if you use a different setup.

## Build in Termux

1.  Install and configure Termux, Java, Gradle, and the Android SDK.

2.  Extract the Android project.

3.  Change to the project root directory (the directory containing
    `settings.gradle` or `settings.gradle.kts`).

4.  Build the debug APK:

    ``` bash
    gradle assembleDebug
    ```

The APK is normally generated at:

``` text
app/build/outputs/apk/debug/app-debug.apk
```

Install it using ADB:

``` bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If building directly on the Android device, make sure the SDK path and
Gradle configuration match your Termux installation.

### Termux Gradle configuration

The development environment used these settings in
`~/.gradle/gradle.properties`:

``` properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
org.gradle.java.home=/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
```

These paths are specific to a typical Termux installation. Adjust them
if your Java or AAPT2 binaries are installed elsewhere. Do not add this
file to the project repository if it contains machine-specific paths.

## Permissions and network access

The app uses Android network permissions to discover and connect to a
local server. The project also enables cleartext HTTP for the local
server connection.

The server's default HTTP URL is:

``` text
http://SERVER_IP:8080
```

Replace `SERVER_IP` with the server device's local IP address.

## Troubleshooting

**No server appears**

-   Start the RIVUNE Server before scanning.
-   Connect the phone and server device to the same Wi-Fi/LAN.
-   Make sure the router does not isolate wireless clients.
-   Check that UDP port `39876` is allowed on the server device.
-   Use the app's retry action after confirming the server is running.

**A server appears but does not connect**

-   Check that TCP port `8080` is allowed.
-   From the phone's browser, open `http://SERVER_IP:8080/api/health`.
-   Confirm the server is advertising the correct HTTP port.

**The app layout is clipped or overlaps system UI**

-   Test on the target device and Android version.
-   Check status-bar, navigation-bar, and display-cutout inset handling.
-   Prefer scrollable content and consistent layout padding over fixed
    screen-height assumptions.
-   Test both gesture navigation and three-button navigation where
    possible.

**Web player is blank or does not load**

-   Confirm the server's web interface opens in the phone's browser.
-   Check the server terminal for HTTP errors.
-   Verify that the WebView URL uses the server's current LAN IP and
    port.

## Compatibility protocol

  Setting              Value
  -------------------- ----------------------------
  UDP discovery port   `39876`
  UDP request          `MOBS_JUKEBOX_DISCOVER_V1`
  Service identifier   `mobs-jukebox`
  Default HTTP port    `8080`
  Health endpoint      `/api/health`

## Project maintenance

When changing discovery behavior, update and test both the Android
client and Node.js server. When changing layouts, test on devices with
different aspect ratios, display cutouts, and system navigation modes.

## License

Add the license and copyright details applicable to your distribution
before publishing this project.
