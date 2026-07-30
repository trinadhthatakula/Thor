// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the zip is allowed to contain.
 *
 * `zipFiles` throws `ZipException: duplicate entry` on a repeated name, which fails the export of
 * an app whose APKs were every one of them readable — so the names have to be settled before the
 * first byte is copied, not discovered at the end.
 */
class StagedApkNamesTest {

    @Test
    fun `base and splits keep the names the installer gave them`() {
        val staged = stagedApkNames(
            listOf(
                "/data/app/~~abc/com.example-1/base.apk",
                "/data/app/~~abc/com.example-1/split_config.arm64_v8a.apk",
                "/data/app/~~abc/com.example-1/split_config.xxhdpi.apk",
            )
        )

        assertEquals(
            listOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk"),
            staged.map { it.second }
        )
    }

    @Test
    fun `a path listed twice is staged once`() {
        // publicSourceDir turning up again in splitPublicSourceDirs: same bytes, so copying them
        // twice would double the zip even if the name collision were somehow survivable.
        val base = "/data/app/~~abc/com.example-1/base.apk"
        val staged = stagedApkNames(listOf(base, base, "/data/app/~~abc/com.example-1/split_a.apk"))

        assertEquals(listOf(base to "base.apk", "/data/app/~~abc/com.example-1/split_a.apk" to "split_a.apk"), staged)
    }

    @Test
    fun `two directories that both hold a base apk both survive`() {
        // Renamed rather than dropped: these are two different APKs, and a bundle missing one of
        // them installs no better than no bundle at all.
        val staged = stagedApkNames(
            listOf(
                "/data/app/~~abc/com.example-1/base.apk",
                "/data/app/~~xyz/com.example-2/base.apk",
                "/data/app/~~def/com.example-3/base.apk",
            )
        )

        assertEquals(listOf("base.apk", "base_2.apk", "base_3.apk"), staged.map { it.second })
        assertEquals(3, staged.map { it.second }.distinct().size)
    }

    @Test
    fun `a name with no extension is not given one`() {
        val staged = stagedApkNames(listOf("/data/app/one/payload", "/data/app/two/payload"))

        assertEquals(listOf("payload", "payload_2"), staged.map { it.second })
    }

    @Test
    fun `a hidden file keeps its leading dot instead of losing its whole name`() {
        // ".apk" is the file's name, not an extension on an empty stem — suffixing it as an
        // extension would produce "_2.apk" and lose what the file was called.
        val staged = stagedApkNames(listOf("/data/app/one/.apk", "/data/app/two/.apk"))

        assertEquals(listOf(".apk", ".apk_2"), staged.map { it.second })
    }

    @Test
    fun `names that differ only in case are both kept`() {
        // The staging dir is the app's own cache on ext4 and the destination is a zip entry;
        // both tell these apart, so renaming one would be a rename nobody asked for.
        val staged = stagedApkNames(listOf("/data/app/one/Base.apk", "/data/app/two/base.apk"))

        assertEquals(listOf("Base.apk", "base.apk"), staged.map { it.second })
    }

    @Test
    fun `nothing in means nothing out`() {
        assertEquals(emptyList<Pair<String, String>>(), stagedApkNames(emptyList()))
    }
}
