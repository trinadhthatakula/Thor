package com.valhalla.thor.rootservice;

/** Bounded observation of one clear-data request. Append fields only; zero means unknown. */
parcelable RootDataClearResult {
    int protocolVersion = 1;
    String requestId = "";
    String daemonInstanceId = "";
    String packageName = "";
    int userId = -1;
    int status = 0;
    int dispatchState = 0;
    int callbackState = 0;
    int reason = 0;
}
