// CorePatchCallerGuardTest.kt
package com.valhalla.thor.data.provider

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePatchCallerGuardTest {
    @Test fun `system uid is allowed`() = assertTrue(isSystemCaller(Process.SYSTEM_UID))
    @Test fun `app uid is rejected`() = assertFalse(isSystemCaller(10234))
    @Test fun `root uid is rejected`() = assertFalse(isSystemCaller(0))
}
