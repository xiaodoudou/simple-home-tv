---
title: Debloat your TV
permalink: /debloat
description: Which preinstalled apps to turn off on China-market Sony and Xiaomi TVs, with adb, and how to undo it.
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

Tried on a MiTV-MFFU0 (Android 14, HyperOS TV).
List: [xiaomi.txt](https://github.com/xiaodoudou/simple-home-tv/blob/main/scripts/debloat/xiaomi.txt).
To turn on adb: Settings > About, click the product model seven times, then Settings > Account & Security, turn on
ADB debugging and allow installing unknown apps.

- `ads`: Video Daily, analytics, MiSight, the push service and cloud control.
- `apps`: the shop, calendar, health, karaoke, weather, translation, web content, Mi Home, quick apps, the screen
  recorder, the toolbox, the game center service, the gallery, and the preinstalled iQIYI (Gitv) and QQ Music.
- `system-extras`: the TV manager, health diagnosis, the phone link services and the Xiaomi app store.
- `screensavers`: Xiaomi's screensavers. Pick another screensaver first, Simple Home's for one.
- `voice`: the XiaoAI assistant and its wake word. Skip it if you talk to the remote.

**Don't touch PatchWall** (`com.mitv.tvhome`). This firmware only lets a system app be the home screen: with PatchWall
off, Home goes to an empty fallback screen that keeps restarting. Simple Home gets around it with an accessibility
service that brings Simple Home back whenever PatchWall comes up. It doesn't catch any keys, so holding volume still
repeats. Turn it on from Simple Home's Settings > Home, or with adb, together with the two grants that keep it on:

```sh
adb shell settings put secure enabled_accessibility_services com.simplehometv/.HomeButton
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.simplehometv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener com.simplehometv/com.simplehometv.RevivalListener
```

The last two matter on Xiaomi. Its firmware force-stops apps at standby and after one stops responding, and Android
then switches the app's accessibility service off. A notification listener comes back after a force-stop, so Simple
Home registers one that reads nothing and only turns the service back on. PatchWall can still show for a moment when
you leave an app with Back.

Keep the settings (`com.xiaomi.mitv.settings`, `com.xiaomi.tvqs`), the inputs player (`com.xiaomi.mitv.tvplayer`),
anything starting with `mitv.service` or `com.mediatek`, the Sogou keyboard, the system updater
(`com.xiaomi.mitv.upgrade`) and the Xiaomi account. Casting (`com.xiaomi.miplay`, `com.xiaomi.mitv.smartshare`) and the
USB media player (`com.xiaomi.mitv.mediaexplorer`) are worth keeping too.

## NVIDIA Shield

Coming soon.
