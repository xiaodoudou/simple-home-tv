package com.simplehometv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test public void comparesVersions() {
        assertTrue(Installer.newer("0.4.10", "0.4.9"));
        assertTrue(Installer.newer("0.5", "0.4.9"));
        assertTrue(Installer.newer("1.0.0", "0.9.9-debug"));
        assertFalse(Installer.newer("0.4.6", "0.4.6"));
        assertFalse(Installer.newer("0.4.6", "0.4.7"));
        assertFalse(Installer.newer("0.4.7-rc.1", "0.4.7"));
    }

    @Test public void singleApk() {
        assertEquals(D + "com.apkupdater.ci-release.apk", Installer.pick(
                Collections.singletonList(D + "com.apkupdater.ci-release.apk"), new String[]{"arm64-v8a"}));
    }
}
