---
name: headset
description: Building, installing, launching, watching and screenshotting the app on a Meta Quest over adb (USB or Wi-Fi), including exercising the background notifications. Use when asked to run the app, test a UI change on the device, or capture a screenshot for a pull request.
---

# Running on a Quest

The headset must be in [developer mode](https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/)
and have allowed this computer's debugging key. `adb` is `~/Android/Sdk/platform-tools/adb` here (an ARM64 build).

```sh
adb devices                                  # the Quest shows as a serial; "unauthorized" means accept the prompt in the headset
adb tcpip 5555 && adb connect <headset-ip>   # once over USB, then unplug; the IP is in Settings → Wi-Fi
./gradlew installDebug                       # build and install the debug APK
adb shell am start -n com.okanetsolutions.briareus.quest/.MainActivity
adb shell am start -n com.okanetsolutions.briareus.quest/.StatusActivity         # the narrow status panel
adb shell am start -n com.okanetsolutions.briareus.quest/.ConversationActivity -d briareus://session/<id>
```

Watch and capture:

```sh
adb logcat --pid=$(adb shell pidof com.okanetsolutions.briareus.quest)           # the app's log only
adb logcat -s AndroidRuntime:E                                                    # crashes
adb exec-out screencap -p > shot.png                                              # what the panel shows
adb shell dumpsys notification --noredact | grep -A12 briareus                    # posted notifications
```

A headset screenshot captures the whole view; the in-headset capture (Oculus button + trigger) is fine too, and it saves
under `/sdcard/Oculus/Screenshots/` to `adb pull`.

## Exercising the notifications

Pair the app with a server that has a project you can start a session on. Put the app in the background (open the
browser), then from the dashboard start a session or send a message that will make the agent ask a question; the
`EventsService` posts the notification with the agent's options as actions and a reply box. The service's own
notification counts working and waiting sessions. A conversation that is open on screen does not notify; cover that
case too when changing `Attention.kt` or `EventsService`.

To reset: **Forget this connection** in the app (erases the sealed token and cache), or
`adb shell pm clear com.okanetsolutions.briareus.quest`.

## When the install is refused

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed APK was signed with a different key (a release from GitHub
versus a local debug build); `adb uninstall com.okanetsolutions.briareus.quest` first. The debug key is per machine.
