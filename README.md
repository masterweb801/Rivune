# RIVUNE for Android

**Music Without Boundaries.**

RIVUNE for Android is the native Android companion for the hosted RIVUNE music-streaming website. The app opens the permanent website inside a secured WebView and connects its web player to Android's native media playback service.

- **Application ID:** com.logicrealm.rivune
- **Platform:** Android 7.0+ (minSdk 24)
- **Language:** Java
- **Web experience:** https://rivune.page.gd/
- **Native playback:** AndroidX Media3 ExoPlayer and MediaSession

## Features

- Opens the hosted RIVUNE website directly; no local server discovery is required.
- WebView configuration supports JavaScript, DOM storage, and web audio playback.
- Restricts navigation to the trusted RIVUNE HTTPS host.
- Bridges website playback commands to native Android playback.
- Uses a Media3 playback service for background audio and system media controls.
- Supports Android media-session integration for notification and lock-screen controls.
- Includes a branded launch splash screen.

## Architecture

The website remains the public music interface. The Android application hosts that interface in a WebView, while the native playback bridge passes track and playback information to the Media3 service.

The app's configured start address is https://rivune.page.gd/. The WebView accepts navigation only to the trusted RIVUNE host over HTTPS.

## Requirements

- Android Studio or a compatible Android SDK/Gradle environment
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin compatible with the project

## Build

From the project root:

    ./gradlew assembleDebug

The debug APK is normally generated at:

    app/build/outputs/apk/debug/app-debug.apk

Install it with ADB:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

You can also use a compatible Gradle installation in Termux:

    gradle assembleDebug

Make sure the Android SDK, Java 17, and Gradle paths are configured for your environment.

## Release signing

Release signing values are read from Gradle user properties or environment variables. Configure these values outside the repository:

- RIVUNE_STORE_FILE
- RIVUNE_STORE_PASSWORD
- RIVUNE_KEY_ALIAS
- RIVUNE_KEY_PASSWORD

The release build validates that all signing values and the keystore file are present. Never commit the keystore or signing passwords.

## Playback integration

The native bridge is implemented in NativePlaybackBridge.java and the background media service is implemented in PlaybackService.java.

The website and Android app communicate through the RIVUNE playback bridge. Keep the message contract and track metadata synchronized when changing either side. Test play, pause, seek, previous/next, notification controls, audio focus, and playback after the app is minimized or the screen is locked.

## Security notes

- The app uses HTTPS and disables cleartext traffic.
- WebView navigation is restricted to the RIVUNE host.
- File and content access are disabled in the WebView.
- Release signing credentials must remain outside Git.

## Related project

- [RIVUNE Server](https://github.com/masterweb801/Rivune-Server) — PHP/MySQL streaming website and admin backend
- [RIVUNE Web App](https://rivune.page.gd/) — hosted music player

---

Built by [Mobashshirul Karim](https://github.com/masterweb801) at [Logic Realm](https://logicrealm.rf.gd/).
