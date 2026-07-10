package com.valhalla.thor.domain.model

/**
 * The Stormbringer launcher hook runs in the launcher's process, so the ContentProvider cannot
 * cryptographically prove the caller is our extension. We bound the blast radius instead: only
 * packages the user already put in the Freezer may be restored. GH#239 / Stormbringer.
 */
fun mayRestore(packageName: String, freezerPackages: Set<String>): Boolean =
    packageName.isNotBlank() && packageName in freezerPackages
