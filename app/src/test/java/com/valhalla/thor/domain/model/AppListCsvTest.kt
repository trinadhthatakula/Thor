// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.presentation.userApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the exported app list.
 *
 * The interesting half is not the columns, it is the quoting. Every string in a row comes from a
 * third party — an app declares its own label — and this file's output is opened in a spreadsheet
 * by definition. A comma that is not quoted shifts every later column of that row silently, and a
 * label starting with `=` is a formula the sheet will evaluate. Neither failure looks like a bug in
 * an export; both look like the data.
 */
class AppListCsvTest {

    private fun rows(csv: String) = csv.trimEnd('\n').lines()

    @Test
    fun `the header names every column, in order`() {
        assertEquals(
            "app_name,package_name,version_name,version_code,installer,is_system,enabled,suspended",
            rows(appListCsv(emptyList())).single()
        )
    }

    @Test
    fun `an empty list still writes the header`() {
        // Nothing calls this with an empty list — the view model refuses first — but a file with a
        // header and no rows is the honest artefact if anything ever does.
        assertEquals(1, rows(appListCsv(emptyList())).size)
    }

    @Test
    fun `a row carries the fields the list shows`() {
        val app = userApp("com.example", appName = "Example").copy(
            versionName = "1.2.3",
            versionCode = 42L,
            installerPackageName = Installers.PLAY_STORE
        )

        assertEquals(
            "Example,com.example,1.2.3,42,com.android.vending,false,true,false",
            rows(appListCsv(listOf(app)))[1]
        )
    }

    @Test
    fun `the package name stands in for an app with no label`() {
        assertEquals(
            "com.example,com.example,,0,,false,true,false",
            rows(appListCsv(listOf(userApp("com.example"))))[1]
        )
    }

    @Test
    fun `an unknown installer is written blank, not as the placeholder`() {
        // "Unknown" is Thor's word for "Android told us nothing". Writing it into a data file would
        // make it indistinguishable from a store that happens to be named that.
        val app = userApp("com.example", installerPackageName = Installers.UNKNOWN)

        assertEquals("", rows(appListCsv(listOf(app)))[1].split(",")[4])
    }

    @Test
    fun `a label containing a comma is quoted so the columns do not shift`() {
        val app = userApp("com.example", appName = "Files, Photos & More")

        assertEquals(
            "\"Files, Photos & More\",com.example,,0,,false,true,false",
            rows(appListCsv(listOf(app)))[1]
        )
    }

    @Test
    fun `a quote inside a field is doubled, per RFC 4180`() {
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
    }

    @Test
    fun `a newline inside a label is quoted rather than splitting the row`() {
        val app = userApp("com.example", appName = "Two\nLines")
        val csv = appListCsv(listOf(app))

        // Still two lines of *record* — the embedded newline lives inside the quotes.
        assertTrue(csv.contains("\"Two\nLines\","))
        assertEquals(3, csv.trimEnd('\n').lines().size) // header + the two halves of one quoted field
    }

    @Test
    fun `a label that starts a formula is neutered`() {
        // The attack this blocks: an app names itself so that opening the export runs something.
        // The quotes in the payload put this through RFC 4180 quoting as well, which is why the
        // apostrophe lands *inside* the outer quotes — a guard applied after quoting would sit
        // outside them and be read as part of the delimiter run rather than the value.
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", csvField("=HYPERLINK(\"http://x\")"))
        assertEquals("'+1", csvField("+1"))
        assertEquals("'-1", csvField("-1"))
        assertEquals("'@SUM(A1)", csvField("@SUM(A1)"))
    }

    @Test
    fun `leading whitespace does not smuggle a formula past the guard`() {
        // Excel strips the leading TAB before deciding, so a naive first-char check on '=' alone
        // would pass this straight through as text and then evaluate it. No outer quotes: a TAB is
        // an ordinary character to CSV, so quoting it would be noise the reader has to strip.
        assertEquals("'\t=cmd", csvField("\t=cmd"))
    }

    @Test
    fun `a formula-looking field that also needs quoting gets both`() {
        assertEquals("\"'=a,b\"", csvField("=a,b"))
    }

    @Test
    fun `an ordinary field is left exactly as it is`() {
        assertEquals("Example", csvField("Example"))
        assertEquals("", csvField(null))
        // A minus *inside* the field is not a formula start, and quoting it would be noise.
        assertEquals("Sub-Zero", csvField("Sub-Zero"))
    }

    @Test
    fun `rows come out in the order they went in`() {
        val csv = rows(
            appListCsv(
                listOf(userApp("com.c"), userApp("com.a"), userApp("com.b"))
            )
        )

        assertEquals(listOf("com.c", "com.a", "com.b"), csv.drop(1).map { it.split(",")[1] })
    }

    @Test
    fun `the file name sorts chronologically and says what it is`() {
        // Two exports a second apart must not collide, and a folder sorted by name must come out in
        // export order — which the field order of the stamp is what guarantees.
        val first = appListFileName(0L)

        assertTrue(first.startsWith("thor-apps-"))
        assertTrue(first.endsWith(".csv"))
        assertTrue(first < appListFileName(60_000L))
    }
}
