# Follow-up: `grantPermission` omits `--user`, unlike every other shell command

**Status:** FIXED in code (2026-07-30, deferred-items batch item #17) — all three gateways now pass
`--user`. **The doc stays open for the device check only**: this cannot be reproduced or regressed in
a JVM test, so the fix is unverified until someone runs it on a work profile. Delete this file once
that check is done.
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

## What shipped

Not the sketch this doc originally carried. That sketch said `--user ${UserHandle.myUserId()}` — the
user *Thor* is running as. What shipped derives the id from the **package's own uid**
(`userIdOf(uid)`, `data/gateway/AndroidUserIds.kt`), because on a work-profile device the two are not
the same: the foreground user is the parent (0) while the profile's packages live in 10, so
`myUserId()` would still have missed. Each gateway keeps its own KDoc explaining this at the call
site.

Resolution failure returns `Result.failure` rather than falling back to user 0 — falling back is the
original bug, so it must not survive as an error path.

Dhizuku carries a caveat the other two do not: it runs as the Device Owner on user 0 and is not
guaranteed to hold `INTERACT_ACROSS_USERS`, so a genuinely cross-user `--user` may be refused. That
is the intended outcome — `pm` reports a real failure instead of silently mutating user 0's copy.

## Remaining: the device check

Cannot be a unit test — all three gateways sit on root/Shizuku/Dhizuku IPC. On a device with a work
profile, via the Permission Manager screen (`TogglePermissionUseCase.kt:19` →
`PermissionRepositoryImpl.kt:82`):

1. Toggle a permission on a package **inside** the work profile and confirm it changes there and not
   on the parent user's copy of the same package.
2. Toggle one on a parent-user package and confirm it still works — the same-user case must be
   unchanged, since `--user <id>` there is what the bare command already did.
3. Repeat under each privilege mode. Root and Shizuku should behave identically; Dhizuku may refuse
   the cross-user case, which is acceptable as long as it *reports* the refusal.

A Xiaomi Second Space is the same shape as a work profile for this purpose and works as a substitute.
