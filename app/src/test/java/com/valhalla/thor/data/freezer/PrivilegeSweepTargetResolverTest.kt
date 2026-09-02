// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.FreezeCandidate
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.FakeFreezeProfileRepository
import com.valhalla.thor.presentation.FakeFreezerRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeSweepTargetResolverTest {

    @Test
    fun `watchlist resolves current members before enqueue`() = runTest {
        val freezer = FakeFreezerRepository(setOf("old.package"))
        val resolver = resolver(freezer = freezer)
        freezer.remove("old.package")
        freezer.add("new.package")

        val spec = resolver.resolve(
            BulkRequest(BulkOp.FREEZE),
            PrivilegeSweepSource.QS_TILE,
        )

        assertEquals(listOf("new.package"), spec.packageNames)
        assertEquals(PrivilegeSweepSource.QS_TILE, spec.source)
    }

    @Test
    fun `profile resolves current members before enqueue`() = runTest {
        val profiles = FakeFreezeProfileRepository(
            listOf(FreezeProfile(7L, "Morning", listOf("old.package")))
        )
        val resolver = resolver(profiles = profiles)
        profiles.update(7L, "Morning", listOf("second.package", "first.package"))

        val spec = resolver.resolve(
            BulkRequest(BulkOp.FREEZE, BulkScope.Profile(7L)),
            PrivilegeSweepSource.PROFILE,
        )

        assertEquals(listOf("first.package", "second.package"), spec.packageNames)
    }

    @Test
    fun `freeze applies UAD and freeze tier safety filters`() = runTest {
        val candidates = mapOf(
            "active.safe" to FreezeCandidate(FreezeState.ACTIVE),
            "active.blocked" to FreezeCandidate(FreezeState.ACTIVE, blockedFromFreeze = true),
            "already.frozen" to FreezeCandidate(FreezeState.FROZEN),
            "missing" to FreezeCandidate(FreezeState.ABSENT, blockedFromFreeze = true),
        )
        val resolver = resolver(
            freezer = FakeFreezerRepository(candidates.keys),
            candidates = candidates,
        )

        val spec = resolver.resolve(
            BulkRequest(BulkOp.FREEZE),
            PrivilegeSweepSource.FREEZER,
        )

        assertEquals(listOf("active.safe"), spec.packageNames)
    }

    @Test
    fun `unfreeze does not apply the freeze safety block`() = runTest {
        val candidates = mapOf(
            "frozen.blocked" to FreezeCandidate(FreezeState.FROZEN, blockedFromFreeze = true),
            "active.blocked" to FreezeCandidate(FreezeState.ACTIVE, blockedFromFreeze = true),
        )
        val resolver = resolver(
            freezer = FakeFreezerRepository(candidates.keys),
            candidates = candidates,
        )

        val spec = resolver.resolve(
            BulkRequest(BulkOp.UNFREEZE),
            PrivilegeSweepSource.SETTINGS,
        )

        assertEquals(listOf("frozen.blocked"), spec.packageNames)
        assertEquals(null, spec.freezerMode)
    }

    @Test
    fun `resolved freezer mode and user id are snapshotted`() = runTest {
        val resolver = resolver(
            preferences = FakePreferenceRepository(
                UserPreferences(freezerMode = FreezerMode.SUSPEND)
            ),
            userId = 12,
        )

        val configured = resolver.resolve(
            BulkRequest(BulkOp.FREEZE),
            PrivilegeSweepSource.FREEZER,
        )
        val explicit = resolver.resolve(
            BulkRequest(BulkOp.FREEZE, mode = FreezerMode.FREEZE),
            PrivilegeSweepSource.PROFILE,
        )

        assertEquals(FreezerMode.SUSPEND, configured.freezerMode)
        assertEquals(FreezerMode.FREEZE, explicit.freezerMode)
        assertEquals(12, configured.userId)
        assertEquals(12, explicit.userId)
    }

    @Test
    fun `selected package list is passed explicitly without BulkScope Selection`() = runTest {
        val resolver = resolver(userId = 10)

        val spec = resolver.resolveSelection(
            operation = PrivilegeSweepOperation.UNFREEZE,
            packageNames = listOf("z.package", "a.package", "z.package"),
            source = PrivilegeSweepSource.APP_LIST,
        )

        assertEquals(PrivilegeSweepOperation.UNFREEZE, spec.operation)
        assertEquals(listOf("a.package", "z.package"), spec.packageNames)
        assertEquals(10, spec.userId)
        assertEquals(PrivilegeSweepSource.APP_LIST, spec.source)
    }

    private fun resolver(
        freezer: FakeFreezerRepository = FakeFreezerRepository(setOf("default.package")),
        profiles: FakeFreezeProfileRepository = FakeFreezeProfileRepository(),
        preferences: FakePreferenceRepository = FakePreferenceRepository(),
        candidates: Map<String, FreezeCandidate> = emptyMap(),
        userId: Int = 0,
    ) = PrivilegeSweepTargetResolver(
        freezerRepository = freezer,
        freezeProfileRepository = profiles,
        preferenceRepository = preferences,
        runtime = object : PrivilegeSweepResolutionRuntime {
            override val userId: Int = userId

            override fun candidatesFor(op: BulkOp): (String) -> FreezeCandidate = { packageName ->
                candidates[packageName] ?: FreezeCandidate(FreezeState.ACTIVE)
            }
        },
    )
}
