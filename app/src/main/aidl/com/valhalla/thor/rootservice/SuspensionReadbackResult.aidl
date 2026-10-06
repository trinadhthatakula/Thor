package com.valhalla.thor.rootservice;

import com.valhalla.thor.rootservice.SuspensionOwner;

/**
 * Compact read-only snapshot. Zero status is UNKNOWN, never a negative suspension answer.
 * Numeric values and bounds are defined by SuspensionReadbackProtocol. Append fields only.
 */
parcelable SuspensionReadbackResult {
    int protocolVersion = 1;
    String packageName = "";
    int userId = -1;
    int status = 0;
    int reason = 0;
    SuspensionOwner[] owners = {};
}
