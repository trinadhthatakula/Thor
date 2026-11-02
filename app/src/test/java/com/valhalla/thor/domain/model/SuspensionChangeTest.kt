// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.domain.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SuspensionChangeTest {
    private val thor = SuspensionOwner("com.valhalla.thor", 0)
    private val wellbeing = SuspensionOwner("com.google.android.apps.wellbeing", 0)
    private val shell = SuspensionOwner("com.android.shell", 0)
    private var owners = setOf<SuspensionOwner>()
    private var owned = setOf<SuspensionOwner>()
    private val writes = mutableListOf<Pair<SuspensionOwner, Boolean>>()

    private suspend fun change(desired: Boolean, sdk: Int = 30, actor: SuspensionOwner = thor) =
        changeOwnedSuspension(desired, sdk, actor,
            read = { OwnedSuspensionSnapshot(owners, owners intersect owned) },
            write = { owner, suspend ->
                writes += owner to suspend
                owners = if (suspend) owners + owner else owners - owner
                if (suspend) owned = owned + owner
            }, canWrite = { it.userId == actor.userId })

    @Test fun `unidentified remaining ownership is not an empty successful restore`() {
        val unknown = SuspensionChange(ownershipUnknown = true)
        assertTrue(unknown.remainingOwners.isEmpty())
        assertTrue(unknown.suspensionRemains)
        assertFalse(SuspensionChange().suspensionRemains)
        assertTrue(SuspensionChange(setOf(wellbeing.packageName)).suspensionRemains)
    }

    @Test fun `known Thor suspension survives privilege mode change without another entry`() = runTest {
        owners = setOf(thor, wellbeing); owned = setOf(thor)
        assertEquals(setOf(wellbeing.packageName), change(true, actor = shell).remainingOwners)
        assertEquals(setOf(thor, wellbeing), owners)
        assertTrue(writes.isEmpty())
    }

    @Test fun `independent suspension survives wellbeing reset`() = runTest {
        owners = setOf(wellbeing)
        val added = change(true)
        assertEquals(setOf(wellbeing.packageName), added.remainingOwners)
        assertEquals(setOf(thor, wellbeing), owners)
        owners -= wellbeing // timer resets, removing only Digital Wellbeing's slot
        assertEquals(setOf(thor), owners)
        change(false)
        assertTrue(owners.isEmpty())
        assertEquals(listOf(thor to true, thor to false), writes)
    }

    @Test fun `unsuspend removes only Thor and names the remaining restriction`() = runTest {
        owners = setOf(thor, wellbeing)
        owned = setOf(thor)
        assertEquals(setOf(wellbeing.packageName), change(false).remainingOwners)
        assertEquals(setOf(wellbeing), owners)
        assertEquals(listOf(thor to false), writes)
    }

    @Test fun `foreign-only unsuspend is a verified non-mutating result`() = runTest {
        owners = setOf(wellbeing)
        assertEquals(setOf(wellbeing.packageName), change(false).remainingOwners)
        assertTrue(writes.isEmpty())
    }

    @Test fun `repeated Thor suspension does not rewrite any owner`() = runTest {
        owners = setOf(thor, wellbeing); owned = setOf(thor)
        assertEquals(setOf(wellbeing.packageName), change(true).remainingOwners)
        assertTrue(writes.isEmpty())
    }

    @Test fun `unmarked shared shell slot is never taken over or removed`() = runTest {
        owners = setOf(shell)
        val failure = runCatching { change(true, actor = shell) }.exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.CANNOT_KEEP_INDEPENDENT, failure.reason)
        assertEquals(setOf(shell.packageName), change(false, actor = shell).remainingOwners)
        assertTrue(writes.isEmpty())
    }

    @Test fun `marked shell entry can be removed without touching wellbeing`() = runTest {
        owners = setOf(shell, wellbeing); owned = setOf(shell)
        assertEquals(setOf(wellbeing.packageName), change(false, actor = shell).remainingOwners)
        assertEquals(listOf(shell to false), writes)
    }

    @Test fun `overwritten shell marker loses ownership and preserves that slot`() = runTest {
        owners = setOf(shell); owned = emptySet()
        change(false, actor = shell)
        assertEquals(setOf(shell), owners)
        assertTrue(writes.isEmpty())
    }

    @Test fun `single-slot Android refuses to replace external suspension`() = runTest {
        for (sdk in listOf(28, 29)) {
            owners = setOf(wellbeing)
            val error = runCatching { change(true, sdk) }.exceptionOrNull() as SuspensionPolicyException
            assertEquals(SuspensionFailureReason.CANNOT_KEEP_INDEPENDENT, error.reason)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `single-slot Android can remove a known Thor suspension`() = runTest {
        owners = setOf(thor); owned = setOf(thor)
        change(false, 29)
        assertTrue(owners.isEmpty())
    }

    @Test fun `unknown ownership refuses before any mutation`() = runTest {
        for (desired in listOf(true, false)) {
            val error = runCatching {
                changeOwnedSuspension(desired, 30, thor, { null }, { a,b -> writes += a to b }, { true })
            }.exceptionOrNull() as SuspensionPolicyException
            assertEquals(SuspensionFailureReason.OWNERSHIP_UNKNOWN, error.reason)
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun `aggregate suspended flag cannot prove our entry was added`() = runTest {
        val foreign = OwnedSuspensionSnapshot(setOf(wellbeing), emptySet())
        val error = runCatching {
            changeOwnedSuspension(true, 30, thor, { foreign }, { a,b -> writes += a to b }, { true })
        }.exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.NOT_VERIFIED, error.reason)
        assertEquals(listOf(thor to true), writes)
    }

    @Test fun `successful call cannot prove our entry was removed`() = runTest {
        val original = OwnedSuspensionSnapshot(setOf(thor, wellbeing), setOf(thor))
        val error = runCatching {
            changeOwnedSuspension(false, 30, thor, { original }, { a,b -> writes += a to b }, { true })
        }.exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.NOT_VERIFIED, error.reason)
        assertEquals(listOf(thor to false), writes)
    }

    @Test fun `read failure after removal stops before another owner`() = runTest {
        var reads = 0
        val error = runCatching {
            changeOwnedSuspension(false, 30, thor,
                { if (reads++ == 0) OwnedSuspensionSnapshot(setOf(thor, shell), setOf(thor, shell)) else null },
                { a,b -> writes += a to b }, { true })
        }.exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.NOT_VERIFIED, error.reason)
        assertEquals(1, writes.size)
    }

    @Test fun `different privilege refuses before all writes`() = runTest {
        val error = runCatching {
            changeOwnedSuspension(false, 30, shell,
                { OwnedSuspensionSnapshot(setOf(thor, shell), setOf(thor, shell)) },
                { a,b -> writes += a to b }, { it == shell })
        }.exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.WRONG_PRIVILEGE, error.reason)
        assertTrue(writes.isEmpty())
    }

    @Test fun `cross-user restrictions are preserved`() = runTest {
        val otherUser = wellbeing.copy(userId = 10)
        owners = setOf(thor, otherUser); owned = setOf(thor)
        change(false)
        assertEquals(setOf(otherUser), owners)
    }

    @Test fun `cancelled mutation is not retried`() = runTest {
        val error = runCatching {
            changeOwnedSuspension(true, 30, thor,
                { OwnedSuspensionSnapshot(emptySet(), emptySet()) },
                { a,b -> writes += a to b; throw CancellationException() }, { true })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, writes.size)
    }
}
