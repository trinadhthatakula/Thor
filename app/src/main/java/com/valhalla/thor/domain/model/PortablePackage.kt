// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.domain.model

/** A classified private copy. Recognition never authorizes installation or flashing. */
sealed interface PortablePackage {
    data class App(val analysis: AnalyzedPackage) : PortablePackage
    data class Kernel(val staged: StagedPackage, val sha256: String) : PortablePackage
}
