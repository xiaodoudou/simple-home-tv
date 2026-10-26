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
one command brings each back.

## Before you start

Turn on network debugging on the TV and connect to it:

```sh
adb connect 192.168.1.20:5555
```

The TV asks to allow debugging the first time. Tick "Always allow from this computer" and accept with the remote.

Disabling and undoing:

```sh
adb shell pm disable-user --user 0 com.example.app    # turn off
adb shell pm enable com.example.app                   # bring back
adb shell pm list packages -d                         # what is off right now
```

The lists below go in one command each, which works the same from PowerShell, cmd or a Linux or macOS shell. A
firmware update can turn some of these back on; run the command again after one.

## Sony Bravia (China firmware)

Tried on a KD-43X85J (firmware `BRAVIA_VH2_M_CN`, Android 12, no Google Play services).

**The China layer:** the Dangbei home and store, Tencent video, NewTV, JD, the Lebo casting app and the partner key
service.

```sh
adb shell 'for p in com.dangbei.TVHomeLauncher com.sony.dangbeimarket com.ktcp.tvvideo com.newtv.login com.jd.smartservicetwo com.hpplay.happyplay.aw com.sony.dtv.partnerkeycn com.qterics.da.product com.sony.dtv.da.service; do pm disable-user --user 0 $p; done'
```

**Sony extras:** the app assistant, My Sony, help and support apps, the eco dashboard, the music and media apps,
timers, the notification center and Living Decor.

```sh
adb shell 'for p in com.sony.dtv.appassistant com.sony.dtv.mysony com.sony.dtv.smarthelp com.sony.dtv.helpguidecontents com.sony.dtv.customersupport com.sony.dtv.customersupport.provider.res com.sony.dtv.ecodashboard com.sony.dtv.osat.music com.sony.dtv.smartmediaapp com.sony.dtv.timers com.sony.dtv.notificationcenter com.sony.dtv.livingfit; do pm disable-user --user 0 $p; done'
```

**Hotel, shop demo and telemetry:** the `b2b` apps are for hotel and commercial setups, the demo apps run the shop
floor loop, and the last ones are bug reports and logging.

```sh
adb shell 'for p in com.sony.dtv.b2b.adminpassword com.sony.dtv.b2b.deviceadminsettings com.sony.dtv.b2b.frameworkextension com.sony.dtv.b2b.hotelmode com.sony.dtv.b2b.importexport com.sony.dtv.b2b.ipcontrolsettings com.sony.dtv.b2b.noderuntime.normal com.sony.dtv.b2b.noderuntime.privilege com.sony.dtv.b2b.noderuntimeextension.bpk com.sony.dtv.b2b.noderuntimeextension.node_20_11_1 com.sony.dtv.b2b.prosettings com.sony.dtv.b2b.rs232csupport com.sony.dtv.b2b.softap com.sony.dtv.b2b.vendorprotocol com.sony.dtv.b2b.webappruntimedrm com.sony.dtv.demosupport com.sony.dtv.demosystemsupport com.sony.dtv.multiscreendemo com.sony.dtv.braviashow com.sony.dtv.sonybugreportsys com.sony.dtv.sonyloglevelsettingsys com.sonyericsson.idd.agent; do pm disable-user --user 0 $p; done'
```

**Features I don't use:** the Bravia Cam, DLNA, recording to USB, and voice search. Skip a group if you use it.

```sh
adb shell 'for p in com.sony.dtv.sensingcamera.fwupdate com.sony.dtv.sensingcamera.settings com.sony.dtv.sensingfusionservice com.sony.dtv.distancealert com.sony.dtv.gestureapp com.sony.dtv.videochatrecord com.sony.huey.dlna.module com.sony.huey.dlna.renderersettings com.sonyericsson.dlna com.sony.dtv.hddmgr com.sony.dtv.recapp com.sony.dtv.reminderservice com.google.android.katniss com.sony.dtv.voicesearch com.google.android.tvrecommendations; do pm disable-user --user 0 $p; done'
```

Keep `com.sony.dtv.ime.chww` (the only keyboard), `com.sony.dtv.tvx` (Live TV and the inputs) and the
`com.sony.dtv.braviasync` apps (HDMI-CEC, so the TV and the receiver turn on together).

## Xiaomi Mi TV (China firmware)

Tried on a MiTV-MFFU0 (Android 14, HyperOS TV). To turn on adb: Settings > About, click the product model seven
times, then Settings > Account & Security, turn on ADB debugging and allow installing unknown apps.

**Ads, analytics and push:** Video Daily, the analytics and MiSight services, the TV push service.

```sh
adb shell 'for p in com.duokan.videodaily com.miui.tv.analytics com.miui.misightservice com.xiaomi.mitv.tvpush.tvpushservice; do pm disable-user --user 0 $p; done'
```

**Xiaomi apps:** the shop, calendar, health, karaoke, weather, translation, web content, Mi Home, quick apps, the
screen recorder, the toolbox, the game center service, the gallery, and the preinstalled iQIYI (Gitv) and QQ Music.

```sh
adb shell 'for p in com.xiaomi.mitv.shop com.mitv.shoplugin com.xiaomi.mitv.calendar com.xiaomi.mitv.health com.xiaomi.mitv.karaoke.service com.xiaomi.tweather com.xiaomi.aitranslate com.xm.webcontent com.xiaomi.smarthome.tv com.miui.hybrid.tv com.xiaomi.screenrecorder com.mitv.toolhouse com.xiaomi.gamecenter.sdk.service.mibox com.xiaomi.tv.gallery com.gitvdemo.video com.tencent.qqmusictv; do pm disable-user --user 0 $p; done'
```

**Voice:** the XiaoAI assistant and its wake word. Skip this one if you talk to the remote.

```sh
adb shell 'for p in com.xiaomi.voicecontrol com.xiaomi.wakeupservice; do pm disable-user --user 0 $p; done'
```

**Don't touch PatchWall** (`com.mitv.tvhome`). This firmware only lets a system app be the home screen: with PatchWall
off, Home goes to an empty fallback screen that keeps restarting. Simple Home gets around it with an accessibility
service that catches the Home button and opens Simple Home instead. Turn it on from Simple Home's Settings > Home, or
with adb:

```sh
adb shell settings put secure enabled_accessibility_services com.simplehometv/.HomeButton
adb shell settings put secure accessibility_enabled 1
```

PatchWall still shows for a moment when you leave an app with Back, and after the TV starts.

Keep the settings (`com.xiaomi.mitv.settings`, `com.xiaomi.tvqs`), the inputs player (`com.xiaomi.mitv.tvplayer`),
anything starting with `mitv.service` or `com.mediatek`, the Sogou keyboard, the system updater
(`com.xiaomi.mitv.upgrade`) and the Xiaomi account. Casting (`com.xiaomi.miplay`, `com.xiaomi.mitv.smartshare`) and the
USB media player (`com.xiaomi.mitv.mediaexplorer`) are worth keeping too.

## NVIDIA Shield

Coming soon.
