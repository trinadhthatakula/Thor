# Approved audit improvements — October 2026

Implemented on `fix/audit-approved-improvements` from dev `4a283d5d`.

## Scope

- **F1:** Optional installer app lock, off by default. Enabling the main lock offers an installer choice; Security settings can change it later. The exported installer and Bolt wait for authentication before staging input when included. Screenshot and Recents protection follows this scope. Authentication remains per activity session, and existing accepted jobs continue.
- **F2 / UX1 / UX5 / UX7:** Refresh saved archives from terminal backup/export queue events, retain legacy job observation, distinguish unavailable sources, reconnect a custom folder, report contextual deletion failures, and add lazy archive search and date/name sorting. The folder choice also changes the existing export destination.
- **UX2 / UI2:** Pause terminal following while reading earlier lines, indicate new output, and offer Jump to latest. Dialog and screen hosts own system insets.
- **UX3 / UX6:** Labeled Bolt consent rows plus Refresh root access and Exit when root admission is unavailable. Existing confirmation and slot protections remain.
- **UI1 / UI4 / UI5:** System bars follow the effective Thor theme; shared bulk actions allow wrapping labels; the distribution legend adapts to its pane and font scale.
- **M1–M5:** Secure passphrase fields with hidden input and non-saveable state, shared Material search input, icon tooltips, selected namespace menu rows, and wavy progress for active work. Existing Material 3 1.5.0-beta01 is retained.

Room 3 is intentionally a separate, final migration PR. This change keeps Room 2.8.5 and database schema v10. UX4, UX8 and UI3 were not approved and are outside this change.

## Device checks still pending

- Cold VIEW/SEND of APK and AnyKernel3 with installer lock off/on; authentication cancellation, enrollment recovery, rotation, incoming intents, screenshots and Recents.
- Live backup/export completion with the hub resumed; unavailable and revoked SAF storage; deletion failure and recovery.
- Logger dragging, keyboard/accessibility scrolling, live appends and jump behavior.
- Gesture/three-button navigation, landscape, large fonts, RTL, keyboard overlap and theme changes.
- Root refresh/exit in Bolt; this change does not validate flashing hardware.
- Tooltip hover/long press, search Back behavior, secure field input/clearing, and reduced-motion progress behavior.

## Local validation

See the pull request for the final build, test and lint results. Compilation and JVM checks do not establish the device behavior listed above.
