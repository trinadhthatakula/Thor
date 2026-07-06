package com.valhalla.thor.domain.model

/**
 * The Strombringer launcher hook runs in the launcher's process, so the ContentProvider cannot
 * cryptographically prove the caller is our extension. We bound the blast radius instead: only
 * packages the user already put in the Freezer may be restored. GH#239 / Strombringer.
 */
fun mayRestore(packageName: String, freezerPackages: Set<String>): Boolean =
    packageName.isNotBlank() && packageName in freezerPackages
