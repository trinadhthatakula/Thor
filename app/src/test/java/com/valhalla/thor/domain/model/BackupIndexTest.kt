// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Wire-format tests for `thor-backup-<timestamp>.json`.
 *
 * The reader of this file is deliberately assumed not to be Thor — a human, a `jq` one-liner, a
 * restore tool written years from now against schema v1 — so the assertions below are made against
 * a *plain* [Json], not against the private instance the model encodes with. Anything only
 * `BackupIndex.decode` can read is not a wire format, it is a Kotlin serialization detail that
 * happens to be written to disk.
 */
class BackupIndexTest {

    private val exported = BackupEntry(
        packageName = "com.example.alpha",
        label = "Alpha",
        versionCode = 7L,
        versionName = "1.0",
        format = BundleFormat.APKS,
        fileName = "Alpha_1.0.apks",
        sizeBytes = 4_096L
    )

    private val failed = BackupEntry(
        packageName = "com.example.beta",
        label = "Beta",
        versionCode = 9L,
        versionName = "2.3",
        format = BundleFormat.APK,
        error = "IllegalStateException: No source path found"
    )

    private fun index(vararg entries: BackupEntry) = BackupIndex(
        createdAt = 1_700_000_000_000L,
        thorVersionCode = 1900,
        entries = entries.toList()
    )

    @Test
    fun `an index round-trips with both a written app and a failed one`() {
        val original = index(exported, failed)

        val decoded = BackupIndex.decode(original.encode())

        // Whole-value equality rather than field-by-field: a field added to either data class is
        // then covered here the day it is added, instead of the day someone remembers.
        assertEquals(original, decoded)
    }

    @Test
    fun `the schema version is written even though it equals its default`() {
        // encodeDefaults exists for exactly this field. It is the one thing a foreign reader must
        // see before it can decide how to parse the rest; omitting it because it happens to match
        // the current default makes v1 indistinguishable from "some Thor that predates versioning".
        val document = Json.parseToJsonElement(index(exported).encode()).jsonObject

        assertEquals(BackupIndex.SCHEMA_VERSION, document["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(1, BackupIndex.SCHEMA_VERSION)
    }

    @Test
    fun `a failed entry is the same shape as a successful one, with nulls where the file would be`() {
        val entries = Json.parseToJsonElement(index(exported, failed).encode())
            .jsonObject["entries"]!!.jsonArray

        val written = entries[0].jsonObject
        val skipped = entries[1].jsonObject

        // No polymorphic discriminator anywhere: a reader that only wants "which file is which
        // app" must not have to learn Kotlin's `"type"` convention to skip the rest, and an
        // unknown discriminator added in v2 would make every v1 reader throw rather than shrug.
        assertFalse(written.containsKey("type"))
        assertFalse(skipped.containsKey("type"))
        assertEquals(written.keys, skipped.keys)

        assertEquals("Alpha_1.0.apks", written["fileName"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, written["error"])
        // fileName and sizeBytes are null exactly when error is not: there is no file to name.
        assertEquals(JsonNull, skipped["fileName"])
        assertEquals(JsonNull, skipped["sizeBytes"])
        assertTrue(skipped["error"]?.jsonPrimitive?.content!!.isNotEmpty())
    }

    @Test
    fun `the format is written as its plain name`() {
        val entries = Json.parseToJsonElement(index(exported, failed).encode())
            .jsonObject["entries"]!!.jsonArray

        assertEquals("APKS", entries[0].jsonObject["format"]?.jsonPrimitive?.content)
        assertEquals("APK", entries[1].jsonObject["format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a v1 reader survives a field a later schema adds`() {
        // The forward-compatibility half of the flat-shape decision: a nullable field added in v2
        // costs a v1 reader nothing, where a new sealed variant would cost it the whole document.
        val v2 = """
            {
              "schemaVersion": 2,
              "createdAt": 1700000000000,
              "thorVersionCode": 2000,
              "dataBackedUp": true,
              "entries": [
                {
                  "packageName": "com.example.alpha",
                  "label": "Alpha",
                  "versionCode": 7,
                  "versionName": "1.0",
                  "format": "APK",
                  "fileName": "Alpha_1.0.apk",
                  "sizeBytes": 4096,
                  "dataFileName": "Alpha_1.0.data.tar"
                }
              ]
            }
        """.trimIndent()

        val decoded = BackupIndex.decode(v2)

        assertEquals(2, decoded.schemaVersion)
        assertEquals(listOf("com.example.alpha"), decoded.entries.map { it.packageName })
        assertNull(decoded.entries.single().error)
    }

    @Test
    fun `an entry that omits the optional fields decodes to nulls`() {
        // Hand-written / regenerated indexes are a supported input (that is what the schema
        // version is for), so the three nullable fields have to be genuinely optional and not
        // just nullable.
        val minimal = """
            {
              "schemaVersion": 1,
              "createdAt": 0,
              "thorVersionCode": 1900,
              "entries": [
                {
                  "packageName": "com.example.alpha",
                  "label": "Alpha",
                  "versionCode": 7,
                  "versionName": "1.0",
                  "format": "XAPK"
                }
              ]
            }
        """.trimIndent()

        val entry = BackupIndex.decode(minimal).entries.single()

        assertEquals(BundleFormat.XAPK, entry.format)
        assertNull(entry.fileName)
        assertNull(entry.sizeBytes)
        assertNull(entry.error)
    }

    @Test
    fun `the manifest is named and typed for the folder it sits in`() {
        // 2026-07-30 11:42:33 in whatever zone the device is in, because the name is for a human
        // sorting a folder; machines read createdAt.
        val at = ZonedDateTime.of(2026, 7, 30, 11, 42, 33, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        assertEquals("thor-backup-20260730-114233-000.json", BackupIndex.fileNameFor(at))
        assertEquals("application/json", BackupIndex.MIME)
    }

    @Test
    fun `two runs a second apart do not write over each other`() {
        // Both file-store paths write by name and delete a collision first, so a fixed name would
        // mean Tuesday's export silently replaces Monday's manifest while leaving Monday's bundles
        // in the folder undescribed — the manifest would be a worse record than the file listing.
        val first = ZonedDateTime.of(2026, 7, 30, 11, 42, 33, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        assertNotEquals(BackupIndex.fileNameFor(first), BackupIndex.fileNameFor(first + 1_000))
    }

    @Test
    fun `two runs in the same second do not write over each other either`() {
        // Not a hypothetical: cancel-and-replace is the ordinary way two runs finish close
        // together, because the cancelled run writes its manifest under NonCancellable while the
        // replacement is already going. At second granularity one of the two manifests is deleted
        // by the other's write and its bundles are left undescribed.
        val first = ZonedDateTime.of(2026, 7, 30, 11, 42, 33, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        assertNotEquals(BackupIndex.fileNameFor(first), BackupIndex.fileNameFor(first + 40))
    }

    @Test
    fun `the name still sorts chronologically when read as text`() {
        // The whole point of the sortable field order: a human with only a file listing gets the
        // runs in order. Adding the millisecond field must not break that at a second boundary.
        val base = ZonedDateTime.of(2026, 7, 30, 11, 42, 33, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val names = listOf(base + 900, base + 1_000, base, base + 60_000)
            .map { BackupIndex.fileNameFor(it) }

        assertEquals(names.sorted(), listOf(names[2], names[0], names[1], names[3]))
    }

    @Test
    fun `the name a reader globs for brackets the name a run writes`() {
        val name = BackupIndex.fileNameFor(0L)

        assertTrue(name.startsWith(BackupIndex.FILE_NAME_PREFIX))
        assertTrue(name.endsWith(BackupIndex.FILE_NAME_SUFFIX))
    }

    // --- The name the index claims the bundle has --------------------------------------------

    @Test
    fun `a bundle file name is the builder's name, spaces and all`() {
        val app = AppInfo(packageName = "com.example.alpha", appName = "My App", versionName = "1.0")

        assertEquals("My_App_1.0.apk", bundleFileNameFor(app, BundleFormat.APK))
        assertEquals("My_App_1.0.apks", bundleFileNameFor(app, BundleFormat.APKS))
        assertEquals("My_App_1.0.xapk", bundleFileNameFor(app, BundleFormat.XAPK))
    }

    @Test
    fun `an app-controlled name cannot put a path into the index`() {
        // appName and versionName come from the app's own manifest. The builder sanitises them
        // because a "/" or ".." would escape its cache dir; this mirrors that rule, so an index
        // entry can never name a file outside the export folder either.
        val hostile = AppInfo(
            packageName = "com.example.evil",
            appName = "../../etc/pwn",
            versionName = "1.0:beta"
        )

        val name = bundleFileNameFor(hostile, BundleFormat.APK)

        assertEquals(".._.._etc_pwn_1.0_beta.apk", name)
        assertFalse(name.contains('/'))
    }

    @Test
    fun `an app with no label falls back to its package name`() {
        val unlabelled = AppInfo(packageName = "com.example.alpha", versionName = "1.0")

        assertEquals("com.example.alpha_1.0.apk", bundleFileNameFor(unlabelled, BundleFormat.APK))
    }

    @Test
    fun `an app that declares no version is not named after the word null`() {
        // versionName is nullable and StringBuilder.append(Any?) writes "null", so this used to
        // export as Alpha_null.apk. A blank one is the same defect with a trailing underscore.
        val noVersion = AppInfo(packageName = "com.example.alpha", appName = "Alpha")
        val blankVersion = AppInfo(
            packageName = "com.example.beta",
            appName = "Beta",
            versionName = "   "
        )

        assertEquals("Alpha.apk", bundleFileNameFor(noVersion, BundleFormat.APK))
        assertEquals("Beta.apks", bundleFileNameFor(blankVersion, BundleFormat.APKS))
        // Still discriminated when it has to be, so the batch's uniqueness rule is unaffected.
        assertEquals(
            "Alpha_com.example.alpha.apk",
            bundleFileNameFor(noVersion, BundleFormat.APK, "com.example.alpha")
        )
    }

    // --- One name per bundle, distinct within the batch ---------------------------------------

    @Test
    fun `two apps under one label are told apart by their package`() {
        // An OEM build and a Play build of the same app is the ordinary case, not a contrived one.
        // Both file-store paths write by name after deleting a collision, so one name for two apps
        // means one file, two manifest entries naming it, and a run that reports two successes.
        val names = uniqueBundleFileNames(
            listOf(
                labelled("com.oem.chat", "Chat") to BundleFormat.APK,
                labelled("com.play.chat", "Chat") to BundleFormat.APK,
            )
        )

        // The first keeps the plain name — the common case must not pay for the rare one — and the
        // discriminator is the package, because that is what a person restoring the folder needs
        // to tell the two apart. A "_2" tail would be unique and unidentifiable.
        assertEquals(listOf("Chat_1.0.apk", "Chat_1.0_com.play.chat.apk"), names)
    }

    @Test
    fun `names that were already distinct are left alone`() {
        val names = uniqueBundleFileNames(
            listOf(
                labelled("com.example.alpha", "Alpha") to BundleFormat.APK,
                labelled("com.example.beta", "Beta") to BundleFormat.APKS,
            )
        )

        assertEquals(listOf("Alpha_1.0.apk", "Beta_1.0.apks"), names)
    }

    @Test
    fun `a name that differs only in case counts as taken`() {
        // The destination can be a FAT-formatted SD card reached through SAF, where Chat_1.0.apk
        // and chat_1.0.apk are one file — a distinction this cannot see and the file system would
        // resolve by overwriting.
        val names = uniqueBundleFileNames(
            listOf(
                labelled("com.oem.chat", "Chat") to BundleFormat.APK,
                labelled("com.play.chat", "chat") to BundleFormat.APK,
            )
        )

        assertEquals(listOf("Chat_1.0.apk", "chat_1.0_com.play.chat.apk"), names)
    }

    @Test
    fun `even one package listed twice ends up with two names`() {
        // A selection cannot hold the same app twice, so this is a formality — it is here so the
        // contract is "always distinct" rather than "distinct unless the caller does something
        // odd", which is the sort of almost-guarantee that later grows an overwrite back.
        val twice = labelled("com.example.alpha", "Alpha")

        val names = uniqueBundleFileNames(List(3) { twice to BundleFormat.APK })

        assertEquals(names.distinct(), names)
        assertEquals(
            listOf(
                "Alpha_1.0.apk",
                "Alpha_1.0_com.example.alpha.apk",
                "Alpha_1.0_com.example.alpha_2.apk"
            ),
            names
        )
    }

    private fun labelled(packageName: String, label: String) =
        AppInfo(packageName = packageName, appName = label, versionName = "1.0")
}
