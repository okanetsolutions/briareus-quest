---
name: headset
description: Building, installing, launching, watching and screenshotting the app on a Meta Quest over adb (USB or Wi-Fi). Use when asked to run the app, test a UI change on the device, or capture a screenshot for a pull request.
---

# Running on a Quest

The headset must be in [developer mode](https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/)
and have allowed this computer's debugging key. `adb` is `~/Android/Sdk/platform-tools/adb` here (an ARM64 build).

```sh
adb devices                                  # the Quest shows as a serial; "unauthorized" means accept the prompt in the headset
adb tcpip 5555 && adb connect <headset-ip>   # once over USB, then unplug; the IP is in Settings → Wi-Fi
./gradlew installDebug                       # build and install the debug APK
adb shell am start -n com.okanetsolutions.briareus.quest/.MainActivity
adb shell am start -n com.okanetsolutions.briareus.quest/.ConversationActivity -d briareus://session/<id>
```

Watch and capture:

```sh
adb logcat --pid=$(adb shell pidof com.okanetsolutions.briareus.quest)           # the app's log only
adb logcat -s AndroidRuntime:E                                                    # crashes
adb exec-out screencap -p > shot.png                                              # what the panel shows
```

A headset screenshot captures the whole view; the in-headset capture (Oculus button + trigger) is fine too, and it saves
under `/sdcard/Oculus/Screenshots/` to `adb pull`.

## Checking live panels

Open the main panel and verify session changes arrive while it is visible. There is no status panel or background
session-alert service. An active voice call holds the events stream open and uses Android's required microphone
foreground-service notification; start the microphone only on the user's request.

To reset: **Forget this connection** in the app (erases the sealed token and cache), or
`adb shell pm clear com.okanetsolutions.briareus.quest`.

## When the install is refused

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed APK was signed with a different key (a release from GitHub
versus a local debug build); `adb uninstall com.okanetsolutions.briareus.quest` first. The debug key is per machine.
