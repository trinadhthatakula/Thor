// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.app.Application
import com.valhalla.bypass.Bypass
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class BypassInvocationTest {
    @Test
    fun `target permission denial is preserved without repeating the operation`() {
        val target = ThrowingTarget()

        val failure = assertThrows(InvocationTargetException::class.java) {
            Bypass.invoke<Unit>(ThrowingTarget::class.java, target, "perform")
        }

        assertSame(target.denial, failure.targetException)
        assertEquals(1, target.calls)
    }

    @Test
    fun `constructor failure does not construct again through another fallback`() {
        val target = ThrowingTarget()

        val failure = assertThrows(InvocationTargetException::class.java) {
            Bypass.newInstance<ThrowingConstructor>(ThrowingConstructor::class.java, target)
        }

        assertSame(target.denial, failure.targetException)
        assertEquals(1, target.calls)
    }

    @Test
    fun `compatible primitive arguments still invoke successfully`() {
        val target = ThrowingTarget()
        val result = Bypass.invoke<Int>(ThrowingTarget::class.java, target, "increment", 3)
        assertEquals(4, result)
    }

    class ThrowingTarget {
        val denial = SecurityException("owner cannot perform this operation")
        var calls = 0

        fun perform() {
            calls++
            throw denial
        }

        fun increment(value: Int): Int = value + 1
    }

    class ThrowingConstructor(target: ThrowingTarget) {
        init {
            target.perform()
        }
    }
}
