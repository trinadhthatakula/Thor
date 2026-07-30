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
        // Grouped with STORAGE rather than with the media groups. PermissionController moves it to
        // READ_MEDIA_VISUAL on Tiramisu+, but a permission may only sit in one chip here, and an app
        // requesting it has always also requested a storage-or-media read — so the STORAGE chip is
        // the one where it changes nothing and the media chip is the one where it would double-count.
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

        put(P + "POST_NOTIFICATIONS", NOTIFICATIONS)
    }

    /**
     * The group for a platform runtime permission, or null if [permission] is not one.
     *
     * Null means "ask the platform" — either it is a custom permission an app declared, or a
     * platform permission that is not dangerous, or one added after this table was written. It never
     * means "no group": a caller that gets null must fall back rather than drop the permission.
     */
    fun groupOf(permission: String): String? = byPermission[permission]

    /** Every permission this table knows, for the test that pins it against the platform's list. */
    val knownPermissions: Set<String> get() = byPermission.keys

    /** Every group this table can produce. */
    val knownGroups: Set<String> get() = byPermission.values.toSet()
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
 */
fun runtimeGroupFor(permission: String, declared: DeclaredPermission?): String? {
    if (declared == null || !declared.isDangerous) return null
    PlatformPermissionGroups.groupOf(permission)?.let { return it }
    return declared.group
        ?.takeUnless { it.isBlank() || it == PlatformPermissionGroups.UNDEFINED }
}
