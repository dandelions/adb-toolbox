# ADBToolbox — Modern Android ADB Toolkit

[中文说明](README.md) · **English**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-purple.svg)](https://kotlinlang.org)
[![Material%203](https://img.shields.io/badge/Design-Material%203-indigo.svg)](https://m3.material.io)

> **ADBToolbox** is an AI-assisted project: a modern Android ADB debugging, screen mirroring, and remote-control toolkit for Android developers and power users.
>
> It is built with **Kotlin, a native JVM/Android networking stack, and Material 3**. The project does not depend on proprietary native `.so` libraries or extracted wrapper shells. Its core protocol implementation is architecture-independent and designed for ARM64, ARM32, and x86_64 devices.

## Features

### 1. High-performance Scrcpy screen mirroring and remote control

- Low-latency 60 FPS streaming with a customized built-in `scrcpy-server`, hardware H.264 encoding, and MediaCodec decoding.
- Accurate touch injection with multi-touch gestures, scrolling, and physical key mapping.
- Multiple control-bar layouts: bottom, left, right, floating ball, or fully hidden immersive mode.
- Remote controls for Back, Home, Recent apps, screen lock/screen-off, volume, and rotation.
- Remote screen-off support to reduce power consumption while keeping the stream active.
- Streaming mode with ADB screenshot fallback for compatibility.
- Surface recreation and stream recovery when the app is sent to the background or resumed from recent apps.

### 2. Audio forwarding and virtual headset mode

- System audio capture on Android 10+ and microphone capture on compatible devices.
- High-quality Opus / PCM audio forwarding.
- Foreground audio playback service that keeps audio running while the mirroring screen is in the background.
- Virtual headset mode that mutes the remote device speaker and routes audio to the controller device.

### 3. Floating controls and two-way clipboard transfer

- Draggable floating control ball with edge snapping and position memory.
- Quick access to virtual headset, fullscreen, screen-off, and mirroring settings.
- Two-way text clipboard transfer between the controller and remote device:
  - Read, edit, and send controller clipboard text to the remote device.
  - Write to the remote clipboard or write and paste immediately into the focused field.
  - Read the remote clipboard and copy it to the controller device.
- Native Scrcpy control flow is preferred, with ADB Shell clipboard commands as a fallback.

### 4. Multiple connection modes and pairing

- Wi-Fi ADB over TCP with configurable IP and port, connection history, and quick reconnect.
- Android 11+ wireless debugging pairing by pairing code and pairing port.
- USB OTG direct connection through Android's native `UsbManager` USB Host mode.
- Kotlin implementation of RSA key generation and ADB authentication.

### 5. App manager

- Filter all, third-party, and system apps.
- Search by package name or app name.
- Extract and back up installed APK files.
- Install APKs without root, uninstall apps, keep app data while uninstalling, disable or enable apps, clear app data, force-stop apps, and launch apps.
- View package name, version, UID, target SDK, installation path, and other app details.

### 6. File manager

- Native ADB binary Sync protocol for direct file transfer.
- Browse `/sdcard` and system directories.
- Navigate multiple directory levels and inspect file attributes.
- Upload and download large files, create folders, rename entries, and delete files or folders recursively.

### 7. ADB terminal

- Interactive ADB Shell with real-time output.
- Quick commands for `top`, `getprop`, `pm list packages`, `df -h`, `logcat`, and other common tasks.
- Command history navigation.

### 8. Device overview and utilities

- Device model, manufacturer, Android version, SDK, build information, battery level and temperature, storage, display resolution, density, ABI, and network information.
- One-tap activation commands for compatible services such as Shizuku, BlackBox, Ice Box, and similar tools.
- Dynamic display resolution and DPI adjustment, with one-tap reset support.

### 9. Fastboot toolbox

- Native USB Bulk Fastboot client implemented in Kotlin.
- Read Bootloader variables.
- Unlock the Bootloader, reboot to system, Recovery, or Bootloader, and perform Fastboot operations supported by the connected device.

## Language support

The app supports **Simplified Chinese and English**.

- The initial language follows the Android system language when no app language has been selected.
- The language can be changed from the language menu on the main screen.
- The Android per-app language setting is supported on Android versions that provide it.
- Page titles, buttons, dialogs, connection messages, mirroring settings, audio presets, and Fastboot operations use localized resources.

## Architecture

```text
com.toolbox.adbhelper
├── App.kt                         # Global Application initialization
├── core                           # Core protocol and business layer
│   ├── adb                        # Native ADB protocol engine
│   │   ├── AdbClient.kt           # High-level operation API
│   │   ├── AdbConnection.kt       # Full-duplex multiplexed channel
│   │   ├── AdbCrypto.kt           # RSA signing and authentication
│   │   ├── AdbMessage.kt          # Binary frame packing and parsing
│   │   ├── AdbStream.kt           # Independent stream abstraction
│   │   ├── AdbSync.kt             # ADB file synchronization protocol
│   │   └── TcpTransport.kt        # TCP socket transport
│   ├── scrcpy                    # Mirroring and control streams
│   │   ├── ScrcpyManager.kt       # Mirroring, touch, key, and clipboard engine
│   │   ├── AudioStreamPlayer.kt   # Opus / PCM audio player
│   │   ├── AudioPlaybackService.kt# Foreground audio service
│   │   └── VirtualHeadsetManager.kt
│   ├── fastboot                   # Fastboot USB protocol
│   ├── usb                        # Android UsbManager OTG connection
│   └── model                      # Data models and presets
└── ui                             # Material 3 UI with ViewBinding
    ├── MainActivity.kt            # Dashboard and feature entry points
    ├── ConnectActivity.kt         # Wi-Fi and USB OTG connection flow
    ├── ScrcpyActivity.kt          # Mirroring console and floating controls
    ├── BlindUnlockActivity.kt     # Blind lock-screen PIN entry
    ├── AppManagerActivity.kt      # App manager
    ├── FileManagerActivity.kt     # File browser and transfer
    ├── TerminalActivity.kt        # Interactive Shell terminal
    └── FastbootActivity.kt        # Fastboot operations
```

## Requirements

- Operating system: Windows, macOS, or Linux
- JDK 17 or JDK 21
- Android SDK API Level 34
- Android Build-Tools 34.0.0
- Gradle 8.7+ with Android Gradle Plugin 8.3.2
- Android 7.0 or later (API 24+)

## Build and install

From the project directory:

```bash
cd adb-toolbox
./gradlew assembleDebug
```

The generated APK is located at:

```text
adb-toolbox/app/build/outputs/apk/debug/app-debug.apk
```

To install it on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Development notes

- The embedded helper JAR is generated by the `buildAppHelperJar` Gradle task before the application is built.
- The app uses a shared debug keystore for local debug and release builds in this project configuration.
- A connected device must have ADB debugging enabled. Wireless debugging on Android 11+ may require pairing before a TCP connection can be established.

## Changelog

### v1.1.23 (Latest)

- Fixed the remote screen status indicator being clipped by other toolbar controls by moving it to a dedicated full-width row.

### v1.1.22

- Added a real-time remote screen status indicator to the mirroring assistant: Off, Dimmed (still on), On, or Unknown.
- The status combines display power state and brightness and refreshes during mirroring.

### v1.1.21

- Migrated the old saved default so the first mirroring session after upgrading performs a real screen-off instead of only reducing brightness.
- An explicit user choice to disable automatic screen-off remains preserved.

### v1.1.20

- The remote screen is now off by default during mirroring; press the physical power button on the remote device when you need to use it directly.
- Fixed the remote screen turning back on when returning to the controller page, rebuilding the Surface, or retrying screenshot capture after the user had turned it off.

### v1.1.19

- Fixed the remote screen turning back on when returning to the controller page, rebuilding the Surface, or retrying screenshot capture after the user had turned it off.
- Separated stream recovery from screen wake-up so wake commands are sent only after an explicit user action.

### v1.1.18

- Fixed Android 14 remote screen-off behavior by correcting the scrcpy power-mode protocol, flushing control packets, and verifying the actual display power state.
- Added an Android 14 power-key fallback when the scrcpy control channel does not take effect, and stopped treating brightness-only dimming as a successful screen-off operation.

### v1.1.17

- Added Simplified Chinese and English app languages, with an in-app language menu and Android per-app language support.
- Added English translations across connection, mirroring, audio, clipboard, file management, app management, terminal, and Fastboot features.
- Added this English project documentation.

### v1.0.3

- Added the two-way clipboard transfer panel to the floating-ball menu.
- Added a Material 3 adaptive application icon with Android monochrome theme support.
- Improved floating-ball dragging, edge protection, and position reset behavior.
- Fixed audio interruption and black-screen issues after returning from the background or recent-apps view.

### v1.0.2

- Added virtual headset insertion and removal to mute the remote speaker.
- Moved audio forwarding to a persistent foreground service.

### v1.0.0

- Initial release with a native Kotlin/JVM ADB protocol stack, Wi-Fi and USB OTG connections, Scrcpy mirroring, app management, file transfer, ADB Shell, and Fastboot tools.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE) if it is present in the repository.
