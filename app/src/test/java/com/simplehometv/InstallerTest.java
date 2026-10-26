package com.simplehometv;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class InstallerTest {

    private static final String D = "/o/r/releases/download/v1/";

    @Test public void picksTheProcessorsBuild() {
        assertEquals(D + "SmartTube_stable_32.68_armeabi-v7a.apk", Installer.pick(Arrays.asList(
                D + "SmartTube_stable_32.68_arm64-v8a.apk", D + "SmartTube_stable_32.68_armeabi-v7a.apk",
                D + "SmartTube_stable_32.68_universal.apk", D + "SmartTube_stable_32.68_x86.apk"),
                new String[]{"armeabi-v7a", "armeabi"}));
    }

    @Test public void fallsBackToUniversal() {
        assertEquals(D + "a_universal.apk", Installer.pick(Arrays.asList(D + "a_x86.apk", D + "a_universal.apk"),
                new String[]{"arm64-v8a"}));
    }

    @Test public void neverPicksARootBuild() {
        assertEquals(D + "app-nonRoot-release.apk", Installer.pick(Arrays.asList(D + "app-root-release.apk",
                D + "app-nonRoot-release.apk"), new String[]{"arm64-v8a"}));
    }

    @Test public void singleApk() {
        assertEquals(D + "com.apkupdater.ci-release.apk", Installer.pick(
                Collections.singletonList(D + "com.apkupdater.ci-release.apk"), new String[]{"arm64-v8a"}));
    }
}
