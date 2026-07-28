# Follow-up: `grantPermission` omits `--user`, unlike every other shell command

**Status:** Deferred — real but unrelated to the tile rework that surfaced it.
**Severity:** Minor–Major (silently targets the wrong user on multi-user/work-profile devices).
**Effort:** small.
**Raised by:** research during the FreezerTileService rework (2026-07-28).

Files: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt:533-543 (fun grantPermission)`,
`ShizukuSystemGateway.kt:154 (override suspend fun grantPermission)`,
`DhizukuSystemGateway.kt:155 (override suspend fun grantPermission)`

## Problem

`SystemGateway.grantPermission` builds `pm grant <pkg> <perm>` with no `--user`. Every other
shell command Thor issues passes one (`Shizuku.kt:65/98/166`).
`PackageManagerShellCommand.runGrantRevokePermission` initialises
`userId = UserHandle.USER_SYSTEM` and has done so unchanged from android-9 through main, so
the grant lands on user 0 regardless of which user Thor is running as.

On a single-user device this is invisible. In a work profile or secondary user it grants the
permission to the wrong user's copy of the package — or fails outright.

This affects the Permission Manager screen today (`TogglePermissionUseCase.kt:19` →
`PermissionRepositoryImpl.kt:82`). It does **not** affect the QS tile work: that rework
deliberately does not use `grantPermission` at all.

## Sketch

Pass `--user ${UserHandle.myUserId()}` in all three gateways, matching the existing Shizuku
call sites. Verify on a device with a work profile before and after.
