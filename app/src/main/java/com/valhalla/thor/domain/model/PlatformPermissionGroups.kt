// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * Which group each platform runtime permission belongs to.
 *
 * **This table exists because the device will not answer the question.** Since Android 10 the
 * framework manifest declares *every* dangerous platform permission with
 * `android:permissionGroup="android.permission-group.UNDEFINED"` — CAMERA, RECORD_AUDIO,
 * ACCESS_FINE_LOCATION, the lot — and the real mapping moved into PermissionController's own
 * hardcoded table. So `PermissionInfo.group` is not deprecated-but-working; for the permissions this
 * filter is entirely about it is *vestigial*. Reading it back gives `UNDEFINED` and nothing else, and
 * a filter built on it offers chips for whatever third-party apps happen to still declare a group of
 * their own — Health, Shizuku's API group — and none for the camera.
 *
 * Verified on an API 37 emulator: `adb shell pm list permissions -g -d` shows the CAMERA,
 * MICROPHONE, CONTACTS, LOCATION, STORAGE, PHONE, CALENDAR, CALL_LOG, SENSORS, NEARBY_DEVICES and
 * NOTIFICATIONS groups all **empty**, with all 47 platform runtime permissions sitting under
 * UNDEFINED.
 *
 * Mirrors PermissionController's `Utils.PLATFORM_PERMISSIONS`. The *group* names are the real ones,
 * so `PackageManager.getPermissionGroupInfo` still resolves each to the localised label the system's
 * own permission dialog uses — the chip and the prompt read the same words, which is the whole point
 * of not translating these in Thor.
 *
 * Being a static table is a feature, not a compromise: it costs no binder call for the ~47 names an
 * average device's apps request most often, and a permission the platform adds later is simply
 * absent rather than wrong. Custom permissions declared by apps still go through
 * `PermissionInfo.group`, which is where that field *is* still honest.
 */
object PlatformPermissionGroups {

    const val PREFIX = "android.permission-group."

    const val CONTACTS = PREFIX + "CONTACTS"
    const val CALENDAR = PREFIX + "CALENDAR"
    const val SMS = PREFIX + "SMS"
    const val STORAGE = PREFIX + "STORAGE"
    const val READ_MEDIA_VISUAL = PREFIX + "READ_MEDIA_VISUAL"
    const val READ_MEDIA_AURAL = PREFIX + "READ_MEDIA_AURAL"
    const val LOCATION = PREFIX + "LOCATION"
    const val CALL_LOG = PREFIX + "CALL_LOG"
    const val PHONE = PREFIX + "PHONE"
    const val MICROPHONE = PREFIX + "MICROPHONE"
    const val ACTIVITY_RECOGNITION = PREFIX + "ACTIVITY_RECOGNITION"
    const val CAMERA = PREFIX + "CAMERA"
    const val SENSORS = PREFIX + "SENSORS"
    const val NEARBY_DEVICES = PREFIX + "NEARBY_DEVICES"
    const val NOTIFICATIONS = PREFIX + "NOTIFICATIONS"

    /** The platform's own "this permission has no group", handed back as a real string. */
    const val UNDEFINED = PREFIX + "UNDEFINED"

    private const val P = "android.permission."

    /**
     * `Build.VERSION_CODES.TIRAMISU`, spelled out.
     *
     * This file is pure Kotlin and stays that way — the running API level arrives as a parameter so
     * a test can name a device instead of being one. `Build.VERSION.SDK_INT` also reads 0 under the
     * mockable android.jar, so a default argument reading it would silently put every unit test on
     * the pre-Tiramisu side of [regrouped] while looking like it tested the device's answer.
     */
    private const val TIRAMISU = 33

    private val byPermission: Map<String, String> = buildMap {
        put(P + "READ_CONTACTS", CONTACTS)
        put(P + "WRITE_CONTACTS", CONTACTS)
        put(P + "GET_ACCOUNTS", CONTACTS)

        put(P + "READ_CALENDAR", CALENDAR)
        put(P + "WRITE_CALENDAR", CALENDAR)

        put(P + "SEND_SMS", SMS)
        put(P + "RECEIVE_SMS", SMS)
        put(P + "READ_SMS", SMS)
        put(P + "RECEIVE_MMS", SMS)
        put(P + "RECEIVE_WAP_PUSH", SMS)
        put(P + "READ_CELL_BROADCASTS", SMS)

        put(P + "READ_EXTERNAL_STORAGE", STORAGE)
        put(P + "WRITE_EXTERNAL_STORAGE", STORAGE)
        // Its placement below Tiramisu. PermissionController moves it to READ_MEDIA_VISUAL on 33+,
        // and so does this table — see [regrouped].
        put(P + "ACCESS_MEDIA_LOCATION", STORAGE)

        put(P + "READ_MEDIA_IMAGES", READ_MEDIA_VISUAL)
        put(P + "READ_MEDIA_VIDEO", READ_MEDIA_VISUAL)
        put(P + "READ_MEDIA_VISUAL_USER_SELECTED", READ_MEDIA_VISUAL)

        put(P + "READ_MEDIA_AUDIO", READ_MEDIA_AURAL)

        put(P + "ACCESS_FINE_LOCATION", LOCATION)
        put(P + "ACCESS_COARSE_LOCATION", LOCATION)
        put(P + "ACCESS_BACKGROUND_LOCATION", LOCATION)

        put(P + "READ_CALL_LOG", CALL_LOG)
        put(P + "WRITE_CALL_LOG", CALL_LOG)
        put(P + "PROCESS_OUTGOING_CALLS", CALL_LOG)

        put(P + "READ_PHONE_STATE", PHONE)
        put(P + "READ_PHONE_NUMBERS", PHONE)
        put(P + "CALL_PHONE", PHONE)
        // Not `android.permission.` — the platform's own odd one out. `Manifest.permission
        // .ADD_VOICEMAIL` is the string below, so the tidy-looking android.permission.ADD_VOICEMAIL
        // this table used to carry matched nothing any device has ever declared: exactly the silent
        // miss the key test above exists to catch, hiding inside a key that looked right.
        put("com.android.voicemail.permission.ADD_VOICEMAIL", PHONE)
        put(P + "USE_SIP", PHONE)
        put(P + "ANSWER_PHONE_CALLS", PHONE)
        put(P + "ACCEPT_HANDOVER", PHONE)

        put(P + "RECORD_AUDIO", MICROPHONE)

        put(P + "ACTIVITY_RECOGNITION", ACTIVITY_RECOGNITION)

        put(P + "CAMERA", CAMERA)

        put(P + "BODY_SENSORS", SENSORS)
        put(P + "BODY_SENSORS_BACKGROUND", SENSORS)

        put(P + "BLUETOOTH_SCAN", NEARBY_DEVICES)
        put(P + "BLUETOOTH_CONNECT", NEARBY_DEVICES)
        put(P + "BLUETOOTH_ADVERTISE", NEARBY_DEVICES)
        put(P + "UWB_RANGING", NEARBY_DEVICES)
        put(P + "NEARBY_WIFI_DEVICES", NEARBY_DEVICES)
        // Android 16's generic ranging permission — PermissionMapping.kt puts it in NEARBY_DEVICES
        // behind `Flags.rangingStackEnabled()`. Listing it needs no API guard of its own: on a
        // device that does not define it, or defines it without the flag that makes it dangerous,
        // `runtimeGroupFor` never reaches the table. Leaving it out was the strictly worse failure —
        // the fallback reads `PermissionInfo.group`, which is UNDEFINED for platform permissions, so
        // an app holding only RANGING dropped out of the Nearby devices chip entirely.
        put(P + "RANGING", NEARBY_DEVICES)

        put(P + "POST_NOTIFICATIONS", NOTIFICATIONS)
    }

    /**
     * The permissions the platform *moves* between groups, and the API level at which the move
     * takes effect.
     *
     * There is exactly one today. Tiramisu split STORAGE into the media groups, and
     * PermissionController's `PermissionMapping.kt` re-homes `ACCESS_MEDIA_LOCATION` from Storage to
     * Photos and videos with it — the permission is meaningless without a media read, and on 33+ the
     * grant that gets you that read is `READ_MEDIA_IMAGES`, not `READ_EXTERNAL_STORAGE`.
     *
     * A single static answer was wrong in one direction whichever one it picked: pinned to STORAGE,
     * a modern photo app that requests `READ_MEDIA_IMAGES` + `ACCESS_MEDIA_LOCATION` and nothing
     * else lands in a Storage chip the system would never show it under; pinned to READ_MEDIA_VISUAL
     * it does the mirror-image thing on a pre-33 device. Filtering by group is only useful if the
     * groups are the ones the user saw in the system's own prompt, and which those are is a property
     * of the device, not of the permission.
     */
    private val regrouped: Map<String, Pair<Int, String>> = mapOf(
        P + "ACCESS_MEDIA_LOCATION" to (TIRAMISU to READ_MEDIA_VISUAL)
    )

    /**
     * The group for a platform runtime permission on a device running [sdkInt], or null if
     * [permission] is not one.
     *
     * Null means "ask the platform" — either it is a custom permission an app declared, or a
     * platform permission that is not dangerous, or one added after this table was written. It never
     * means "no group": a caller that gets null must fall back rather than drop the permission.
     *
     * [sdkInt] is only consulted for the handful of permissions in [regrouped]; everything else has
     * had one home for as long as it has existed. It is a parameter rather than a read of
     * `Build.VERSION.SDK_INT` so the boundary is assertable from both sides — see [TIRAMISU].
     */
    fun groupOf(permission: String, sdkInt: Int): String? {
        regrouped[permission]?.let { (since, group) -> if (sdkInt >= since) return group }
        return byPermission[permission]
    }

    /** Every permission this table knows, for the test that pins it against the platform's list. */
    val knownPermissions: Set<String> get() = byPermission.keys

    /**
     * The permissions whose group depends on the API level, for the test that keeps them listed in
     * [byPermission] too — a regrouped entry missing its base row would resolve on new devices and
     * vanish on old ones.
     */
    val regroupedPermissions: Set<String> get() = regrouped.keys

    /** Every group this table can produce, on any API level. */
    val knownGroups: Set<String>
        get() = byPermission.values.toSet() + regrouped.values.map { it.second }
}

/**
 * What the *running* platform says about a permission — null, at the call site, meaning it does not
 * define that permission at all.
 *
 * [group] is `PermissionInfo.group` verbatim, UNDEFINED and blanks included; [runtimeGroupFor] is
 * what decides whether either is usable.
 */
data class DeclaredPermission(val isDangerous: Boolean, val group: String?)

/**
 * The group [permission] should be filtered under, or null to leave it out of the index entirely.
 *
 * **The device is asked first, and the table only overrides the answer's *group*.** That order is
 * the whole point: `PackageInfo.requestedPermissions` lists what a manifest *declares*, which
 * includes permissions the Android version in front of us has never heard of. An APK declaring
 * `POST_NOTIFICATIONS` runs fine on API 28 and an APK declaring `READ_MEDIA_IMAGES` runs fine on
 * API 32 — the declaration is simply inert. Consulting [PlatformPermissionGroups] before the
 * platform put both of those in a chip, so Thor offered a Notifications filter on a device with no
 * notification permission and listed apps under it that hold no such capability. Thor's supported
 * range starts at API 28, so this is not a hypothetical device.
 *
 * [declared] `== null` is that case: `getPermissionInfo` throws `NameNotFoundException` for a
 * permission this OS does not define, and that is the authoritative "no". A permission that exists
 * but is not `dangerous` is refused for the same reason it always was — INTERNET matching 400 apps
 * is not a filter.
 *
 * Only once both hold does the table speak, and it speaks about the group alone: since Android 10
 * `PermissionInfo.group` reads UNDEFINED for every dangerous platform permission (see
 * [PlatformPermissionGroups]), so the field is useless for exactly the permissions this filter is
 * about and honest for the custom ones it is not.
 *
 * [sdkInt] is the running API level, and it decides the *group* for the few permissions the platform
 * itself re-homes across versions — it is not a second existence check. The device's own answer is
 * still the only thing that says whether the permission is real here; see
 * [PlatformPermissionGroups.groupOf].
 */
fun runtimeGroupFor(permission: String, declared: DeclaredPermission?, sdkInt: Int): String? {
    if (declared == null || !declared.isDangerous) return null
    PlatformPermissionGroups.groupOf(permission, sdkInt)?.let { return it }
    return declared.group
        ?.takeUnless { it.isBlank() || it == PlatformPermissionGroups.UNDEFINED }
}
