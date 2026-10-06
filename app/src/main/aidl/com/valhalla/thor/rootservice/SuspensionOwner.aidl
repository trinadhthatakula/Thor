package com.valhalla.thor.rootservice;

/** A suspension identity, whose Android user may differ from the target user's. */
parcelable SuspensionOwner {
    String packageName = "";
    int userId = -1;
}
