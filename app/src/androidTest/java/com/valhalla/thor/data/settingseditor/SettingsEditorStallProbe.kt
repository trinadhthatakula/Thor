// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor;

/** Test-APK-only app_process entry point: stalls without touching settings or spawning children. */
public final class SettingsEditorStallProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("THOR_STALL_PID:" + android.os.Process.myPid());
        System.out.flush();
        while (true) Thread.sleep(1000);
    }
}
