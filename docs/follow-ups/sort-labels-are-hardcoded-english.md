# The sort labels in the filter sheet are hardcoded English

**Filed:** 2026-07-30, while building the permission filter (#285).
**Tier:** 3 · **Effort:** small (8 strings × 5 locales) · **Status:** decision open — see *Scope*.

## What

`SortBy.asGeneralName()` and `SortOrder.asGeneralName()` return English string literals:

```kotlin
// app/src/main/java/com/valhalla/thor/domain/model/SortBy.kt
fun asGeneralName(): String = when (this) {
    NAME -> "Name"
    SIZE -> "Size"
    INSTALL_DATE -> "Install Date"
    …
}
```

`AppList.kt` renders them directly — `Text(item.asGeneralName(), …)` — with no `stringResource`
in the path, so a French or Arabic user opens the filter sheet's **Sort** tab and reads eight
English labels.

## Why it is filed rather than fixed

It was found because the *filter type* labels had the identical bug: `FilterType.asGeneralName()`
returned `"Active State"` / `"Installation Source"` as literals, sitting directly above chips
(`Activa`, `Congelada`, `Suspendida`) that were already translated. #285 had to touch that function
anyway — it was adding a third case — so fixing it there was free, and it now returns a
`@StringRes Int`.

`SortBy` is the same bug in the next tab down, but nothing in #285 touches it. Folding it in would
have meant 8 new strings in 5 locales (40 entries) of machine translation reviewed by nobody, in a
PR about permissions. That is the kind of unrelated surface a reviewer cannot check.

## The fix, when it is taken

Mechanical, and `FilterType.kt` is now the worked example:

1. Change both `asGeneralName()` signatures to `@StringRes fun asGeneralName(): Int`.
2. Add `sort_by_name`, `sort_by_size`, `sort_by_install_date`, `sort_by_last_updated`,
   `sort_by_version_code`, `sort_by_version_name`, `sort_by_target_sdk`, `sort_by_min_sdk` to
   `values/strings.xml` and to `-ar`, `-es`, `-fr`, `-zh-rCN`. Lint's `MissingTranslation` is fatal,
   so a locale left out fails the build rather than shipping.
3. Wrap the two call sites in `AppList.kt` in `stringResource(…)`.

`SortOrder.asGeneralName()` is a special case: it is **dead**. The order row already uses
`stringResource(R.string.ascending)` / `R.string.descending` directly, so that function has no
caller. Delete it rather than translate it — and check `icon()`, `flip()` and `angle()` for the
same, since they are on the same enum.

## Not to be confused with

The **chip** labels, which have always been localised, and the permission-group chip labels, which
come from the platform (`PermissionGroupInfo.loadLabel`) and are therefore translated into every
locale Android itself supports. This is only the *category* and *sort-key* labels in the sheet.
