// CorePatchAudit.kt
package com.valhalla.thor.data.corepatch

import org.koin.core.annotation.Single

data class CorePatchAuditEntry(
    val timestampMillis: Long,
    val pkg: String,
    val oldSigner: String?,
    val newSigner: String,
    val capability: String,
    val downgrade: Boolean,
    val result: String,
)

interface CorePatchAudit {
    fun append(entry: CorePatchAuditEntry)
    fun all(): List<CorePatchAuditEntry>
}

@Single(binds = [CorePatchAudit::class])
class InMemoryCorePatchAudit(private val max: Int = 200) : CorePatchAudit {
    private val entries = ArrayDeque<CorePatchAuditEntry>()

    @Synchronized override fun append(entry: CorePatchAuditEntry) {
        entries.addLast(entry)
        while (entries.size > max) entries.removeFirst()
    }

    @Synchronized override fun all(): List<CorePatchAuditEntry> = entries.toList()
}
