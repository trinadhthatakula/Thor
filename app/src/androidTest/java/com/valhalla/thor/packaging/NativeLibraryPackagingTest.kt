// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.packaging

import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.asComposePath
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real JNI consumers, including the conic conversion still native on API 34+. */
@RunWith(AndroidJUnit4::class)
class NativeLibraryPackagingTest {
    @Test
    fun graphicsPathConicsConvertToQuadraticsFromExtractedLibrary() {
        val circle = Path().apply {
            addCircle(CENTER, CENTER, RADIUS, Path.Direction.CW)
        }.asComposePath()
        val original = PathIterator(circle, PathIterator.ConicEvaluation.AsConic).asSequence().toList()
        assertTrue("The fixture must exercise native conic conversion", original.any {
            it.type == PathSegment.Type.Conic
        })

        val converted = PathIterator(
            circle,
            conicEvaluation = PathIterator.ConicEvaluation.AsQuadratics,
            tolerance = 0.05f,
        ).asSequence().toList()
        assertFalse("Conics were not converted", converted.any { it.type == PathSegment.Type.Conic })
        val quadratics = converted.filter { it.type == PathSegment.Type.Quadratic }
        assertTrue("No quadratic approximation was produced", quadratics.isNotEmpty())

        // Check the native result's geometry, not just whether loading the library succeeded.
        quadratics.forEach { segment ->
            val points = segment.points
            assertTrue("Non-finite native output", points.all { it.isFinite() })
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
                val remaining = 1f - t
                val x = remaining * remaining * points[0] + 2f * remaining * t * points[2] + t * t * points[4]
                val y = remaining * remaining * points[1] + 2f * remaining * t * points[3] + t * t * points[5]
                assertEquals("Converted curve left the circle", RADIUS, hypot(x - CENTER, y - CENTER), 0.2f)
            }
        }
        assertExtractedLibraryIsMapped("androidx.graphics.path")
    }

    @Test
    fun multiProcessDataStoreCanPersistAndReopenUsingExtractedCounter() = runBlocking {
        withTimeout(15_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "native-counter-${UUID.randomUUID()}")
            assertTrue("Could not create isolated test directory", directory.mkdir())
            val file = File(directory, "counter.data")
            try {
                // The multiprocess factory invokes the real native shared counter even when this
                // smoke test uses one process. Thor's normal preferences remain untouched.
                withDataStore(file) { store ->
                    assertEquals(0, store.data.first())
                    repeat(5) { index ->
                        assertEquals(index + 1, store.updateData { it + 1 })
                    }
                    assertEquals(5, store.data.first())
                }
                // The first scope has stopped before another store opens the same file.
                withDataStore(file) { reopened ->
                    assertEquals(5, reopened.data.first())
                    assertEquals(6, reopened.updateData { it + 1 })
                    assertEquals(6, reopened.data.first())
                }
                assertExtractedLibraryIsMapped("datastore_shared_counter")
            } finally {
                assertTrue("Could not remove isolated test data", directory.deleteRecursively())
            }
        }
    }

    private suspend fun withDataStore(file: File, block: suspend (DataStore<Int>) -> Unit) {
        val job = SupervisorJob()
        val store = MultiProcessDataStoreFactory.create(
            serializer = CounterSerializer,
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(store)
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    private fun assertExtractedLibraryIsMapped(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = File(context.applicationInfo.nativeLibraryDir, System.mapLibraryName(name))
        assertTrue("Target APK library was not extracted: $library", library.isFile)
        val mappings = File("/proc/self/maps").useLines { lines ->
            lines.filter { it.endsWith(library.canonicalPath) }.toList()
        }
        // Prevent a copy in the instrumentation APK from masking a target APK packaging failure.
        assertTrue("Target APK library is not mapped: $library", mappings.isNotEmpty())
        Log.i(TAG, "API=${Build.VERSION.SDK_INT}, ABIs=${Build.SUPPORTED_ABIS.joinToString()}, library=$library")
        mappings.forEach { Log.i(TAG, it) }
    }

    private object CounterSerializer : Serializer<Int> {
        override val defaultValue = 0

        override suspend fun readFrom(input: InputStream): Int =
            input.readBytes().toString(Charsets.UTF_8).toInt()

        override suspend fun writeTo(t: Int, output: OutputStream) {
            output.write(t.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        const val TAG = "NativeLibraryPackaging"
        const val CENTER = 128f
        const val RADIUS = 64f
    }
}
