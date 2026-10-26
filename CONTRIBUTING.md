# Contributing

This page gets you from a clone to a change that passes CI: setting up, running the app while you work on it, the
checks, and how commits are written here.

## Setting up

You need JDK 17 and the Android SDK. Android Studio brings both; from a terminal, point `JAVA_HOME` at its bundled JDK
(`jbr`) and let Gradle find the SDK through `ANDROID_HOME` or a `local.properties` with `sdk.dir`.

```sh
git clone https://github.com/xiaodoudou/simple-home-tv.git
cd simple-home-tv
./gradlew assembleDebug
```

The app has no AndroidX and no runtime dependencies. Keep it that way unless there is a good reason.

## Running it

**On an emulator.** Create an Android TV device (1080p, API 31 or later) and install the debug build. The emulator
has no TV provider and no HDMI inputs, so debug builds can fill the content rows with made-up items:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.simplehometv touch files/demo
adb shell cmd package set-home-activity com.simplehometv/.HomeActivity
```

Drive it with `adb shell input keyevent KEYCODE_DPAD_DOWN` and friends. `input keyevent --longpress
KEYCODE_DPAD_CENTER` counts as holding OK.

**On a TV.** `scripts/deploy.sh` builds, runs the tests, installs and brings the home to the front. Set `TV` to the
TV's adb address first (`TV=192.168.1.20:5555 scripts/deploy.sh`). It presses Home on the TV, so don't run it while
someone is watching.

HDMI previews and real content rows only work on a real TV.

## Checks

CI runs these on every push and pull request; run them before you push:

```sh
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Lint errors fail the build, warnings don't. Unit tests cover the pure logic (dock layout, grid navigation, pins, the OK
key, aerial clip choice); anything touching views is checked on an emulator or a TV.

## Code

Match the file you are in: plain Java 17, views built in code, no XML layouts. Comments say why, not what, and stay
short.

## Commit messages

The subject is a plain sentence saying what changes for the person using the TV (or the code), in the present tense,
without a type prefix or a final period. Name the area first when it helps:

```
Down from the home opens All apps under the same column
Settings: pick the app store in Apps
CI: lint, unit tests and a debug build on every push
```

The body, wrapped at about 80 characters, explains why: what was wrong, what the change does about it, and anything a
reviewer should know (what was tried and dropped, what is left for later). The diff already says what changed.

## Pull requests

Open them against `main`. Describe the problem, the change and how you checked it, with a screenshot for anything on
screen. CI must be green before review.

Security problems don't go in a public issue or pull request: see [SECURITY.md](SECURITY.md).

## Releasing

A release is a tag. Bump `versionCode` and `versionName` in `app/build.gradle`, commit that on `main` ("Release 0.3.0"),
then tag it and push the tag:

```sh
git tag v0.3.0
git push origin v0.3.0
```

The release workflow checks that the tag names `versionName` and sits on `main`, runs CI, builds the APK signed with
the release key from the repository secrets, and creates the GitHub release with it.
