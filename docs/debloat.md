---
title: Debloat your TV
permalink: /debloat
description: Which preinstalled apps to turn off on China-market Sony and Xiaomi TVs and the NVIDIA Shield, with adb, and how to undo it.
---

# Debloat your TV

China-market TVs come with a whole second layer of apps: their own home screen, app stores, video services, ads,
analytics and push. None of it is needed to watch anything, and most of it runs in the background. These are the
lists I use on my own TVs. Each one was tried on a real TV, and everything still works afterwards: settings, HDMI
inputs, the remote, casting, updates.

Nothing here deletes anything. Apps are **disabled** for the user, which hides them and stops them from running, and
the same script brings them back.

## Before you start

Turn on network debugging on the TV and connect to it:

```sh
adb connect 192.168.1.20:5555
```

The TV asks to allow debugging the first time. Tick "Always allow from this computer" and accept with the remote.

## Running it

The lists live in the repo, one file per brand in
[scripts/debloat](https://github.com/xiaodoudou/simple-home-tv/tree/main/scripts/debloat), split into groups. The
script disables every app in a list, skips the groups you name, and with `--undo` turns them all back on.

From Linux, macOS or Git Bash:

```sh
scripts/debloat.sh xiaomi
scripts/debloat.sh xiaomi --skip voice
scripts/debloat.sh xiaomi --undo
```

From PowerShell:

```powershell
.\scripts\debloat.ps1 xiaomi
.\scripts\debloat.ps1 xiaomi -Skip voice
.\scripts\debloat.ps1 xiaomi -Undo
```

With more than one TV connected, add `--serial 192.168.1.20:5555` (`-Serial` in PowerShell). A firmware update can
turn some apps back on; run the script again after one. To check what is off right now:

```sh
adb shell pm list packages -d
```

## Sony Bravia (China firmware)

Tried on a KD-43X85J (firmware `BRAVIA_VH2_M_CN`, Android 12, no Google Play services).
List: [sony.txt](https://github.com/xiaodoudou/simple-home-tv/blob/main/scripts/debloat/sony.txt).

- `china`: the Dangbei home and store, Tencent video, NewTV, JD, the Lebo casting app and the partner key service.
- `extras`: the app assistant, My Sony, help and support, the eco dashboard, the music and media apps, timers, the
  notification center and Living Decor.
- `hotel-demo-telemetry`: hotel and commercial setups, the shop floor demo, bug reports and logging.
- `camera`, `dlna`, `usb-recording`, `voice`: features I don't use. Skip the ones you do.

Keep `com.sony.dtv.ime.chww` (the only keyboard), `com.sony.dtv.tvx` (Live TV and the inputs) and the
`com.sony.dtv.braviasync` apps (HDMI-CEC, so the TV and the receiver turn on together).

## Xiaomi Mi TV (China firmware)

Tried on a MiTV-MFFU0 and a MiTV-MFTR0 (Android 14, HyperOS TV).
To turn on adb: Settings > About, click the product model seven times, then Settings > Account & Security, turn on
ADB debugging and allow installing unknown apps.

> **On Xiaomi, install `simple-home-tv-xiaomi-<version>.apk`, not the normal APK.** It is Simple Home under the
> package name `com.ts.launcher`, the only way a third-party home survives PatchWall being turned off. The two are
> separate apps: moving from the normal APK starts with fresh settings.

In order:

```sh
adb install simple-home-tv-xiaomi-<version>.apk
adb shell pm grant com.ts.launcher android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener com.ts.launcher/com.simplehometv.RevivalListener
adb shell settings put secure enabled_accessibility_services com.ts.launcher/com.simplehometv.HomeButton
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure screensaver_components com.ts.launcher/com.simplehometv.Screensaver
scripts/debloat.sh xiaomi
# wait 30 s before any reboot
```

The debloat turns PatchWall off last, and only once `com.ts.launcher` is installed.

List: [xiaomi.txt](https://github.com/xiaodoudou/simple-home-tv/blob/main/scripts/debloat/xiaomi.txt).

- `ads`: Video Daily, analytics, MiSight, the push service and cloud control.
- `apps`: the shop, calendar, health, karaoke, weather, translation, web content, Mi Home, quick apps, the screen
  recorder, the toolbox, the game center service, the gallery, and the preinstalled iQIYI (Gitv) and QQ Music.
- `system-extras`: the TV manager, health diagnosis, the phone link services and the Xiaomi app store.
- `screensavers`: Xiaomi's screensavers. Pick another screensaver first, Simple Home's for one.
- `voice`: the XiaoAI assistant and its wake word, which keeps about two-thirds of a core busy. Skip it
  (`--skip voice`) if you talk to the remote.
- `patchwall`: Xiaomi's home (`com.mitv.tvhome`), skipped unless the Xiaomi build is installed.

**Why the Xiaomi build.** This firmware only lets allowlisted package names be the home
screen, and `com.ts.launcher` is on the list. The `simple-home-tv-xiaomi` APK is Simple Home under that name: with
PatchWall (`com.mitv.tvhome`) off it is the real home, at boot and on every Home press. Wait half a minute before
rebooting after `disable-user`, or PatchWall comes back before the change is written.

The grant, the listener and the accessibility lines keep ADB debugging on: the firmware turns it off around standby, and the service turns it back on
(turned off by hand with the TV in use, it stays off). The notification listener brings the service back after the
firmware force-stops apps at standby. Undo with `adb shell pm enable --user 0 com.mitv.tvhome` and a reboot. Under
any other package name, PatchWall off leaves Home on a black fallback screen. Found by
[cleanyourtv](https://github.com/Gathin23/cleanyourtv/blob/main/docs/devices/xiaomi-mitv-mftr0-debloat-home.md).

With the normal APK (`com.simplehometv`) leave PatchWall on: the same service, turned on for that package, brings
Simple Home back when PatchWall comes up, before it draws a frame after a Home press. That needs usage access too
(`adb shell appops set com.simplehometv GET_USAGE_STATS allow`), and PatchWall can still show for a moment when you
leave an app with Back.

Keep the settings (`com.xiaomi.mitv.settings`, `com.xiaomi.tvqs`), the inputs player (`com.xiaomi.mitv.tvplayer`),
anything starting with `mitv.service` or `com.mediatek`, the Sogou keyboard, the system updater
(`com.xiaomi.mitv.upgrade`, unless you [stop updates](#stopping-firmware-updates)) and the Xiaomi account. Casting (`com.xiaomi.miplay`, `com.xiaomi.mitv.smartshare`) and the
USB media player (`com.xiaomi.mitv.mediaexplorer`) are worth keeping too.

## NVIDIA Shield

Tried on a SHIELD Android TV (darcy, Shield Experience on Android 11).
List: [shield.txt](https://github.com/xiaodoudou/simple-home-tv/blob/main/scripts/debloat/shield.txt).

- `google-home`: the Google TV home and its recommendation feed. Run it only once Simple Home is the home screen,
  since the Shield picks Google's home over any other while it is on.
- `preinstalls`: Prime Video, Google TV (films), Play Games and YouTube Music.
- `nvidia-extras`: NVIDIA Games, game broadcasting, feedback, usage stats, diagnostics, the developer widget, the
  input viewer, the beta program and the factory tools.
- `google-extras`: Google feedback, the photo viewer, printing and the basic screensaver.
- `voice`: Google Assistant and its wake word. Skip it if you talk to the remote: without it the microphone button
  does nothing.

Keep the updater (`com.nvidia.ota`, unless you [stop updates](#stopping-firmware-updates)), Play services, Cast (`com.google.android.apps.mediashell`), the TV provider
(`com.android.providers.tv`, which fills the Continue watching rows), `com.nvidia.irtuner` (the remote's TV volume and
power), `com.nvidia.shieldtech.hooks` and `com.nvidia.shield.appselector` (the remote's buttons), and the pairing and
Bluetooth apps.

## Stopping firmware updates

An update can turn disabled apps back on, bring the stock home back or move things the lists rely on. To keep a TV
the way it is, turn its updater off. You stop getting security fixes too, so decide which matters more to you.

- Sony: `com.sony.dtv.systemupdate` and `com.sony.dtv.update`
- Xiaomi: `com.xiaomi.mitv.upgrade`
- Shield: `com.nvidia.ota`

It's the same command the lists use, `adb shell pm disable-user --user 0 <package>`, and
`adb shell pm enable <package>` turns updates back on.
