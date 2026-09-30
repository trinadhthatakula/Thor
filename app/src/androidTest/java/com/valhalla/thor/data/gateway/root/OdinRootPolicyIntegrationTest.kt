package com.valhalla.thor.data.gateway.root

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit host-controlled Magisk policy test; never include in an uncoordinated suite. */
@RunWith(AndroidJUnit4::class)
class OdinRootPolicyIntegrationTest {
    @Test fun magiskRevocationAndGrantRefreshFollowFreshPolicy() {
        check(InstrumentationRegistry.getArguments().getString("odinPolicyToggle") == "true") { "Explicit host policy coordination required" }
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val ready = File(cache, "odin-policy-ready")
        val denied = File(cache, "odin-policy-denied")
        val granted = File(cache, "odin-policy-granted")
        ready.delete(); denied.delete(); granted.delete()
        try {
            val initial = Shell.refreshRootAvailability()
            assertEquals(initial.toString(), RootAvailabilityKind.ROOT, initial.kind)
            val existing = Shell.shell
            ready.writeText("ready")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            while (!denied.exists() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue("Host must configure denial", denied.exists())
            val identity = arrayListOf<String?>()
            assertEquals(0, existing.newJob().add("id").to(identity, null).exec().code)
            assertTrue("Existing identity may remain root after new requests denied", identity.any { it?.contains("uid=0") == true })
            // Magisk caches policy observations briefly after a direct test-harness DB update.
            // Establish that fresh application su requests are denied before testing Odin refresh.
            var freshDenied = false
            while (System.nanoTime() < deadline) {
                val probe = ProcessBuilder("su", "-c", "id").start()
                if (probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() != 0) { freshDenied = true; break }
                probe.destroy()
                Thread.sleep(3500)
            }
            assertTrue("Magisk must deny a fresh app request", freshDenied)
            val deniedRefresh = Shell.refreshRootAvailability()
            assertEquals(deniedRefresh.toString(), RootAvailabilityKind.NON_ROOT, deniedRefresh.kind)
            assertEquals(false, Shell.isAppGrantedRoot)
            ready.writeText("denial-verified")
            while (!granted.exists() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue("Host must restore grant", granted.exists())
            var freshGranted = false
            while (System.nanoTime() < deadline) {
                val probe = ProcessBuilder("su", "-c", "id").start()
                if (probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0) { freshGranted = true; break }
                probe.destroy()
                Thread.sleep(3500)
            }
            assertTrue("Magisk must grant a fresh app request", freshGranted)
            val grantRefresh = Shell.refreshRootAvailability()
            assertEquals(grantRefresh.toString(), RootAvailabilityKind.ROOT, grantRefresh.kind)
            assertEquals(true, Shell.isAppGrantedRoot)
        } finally { ready.delete(); denied.delete(); granted.delete() }
    }

}
