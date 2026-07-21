package com.valhalla.thor.rootservice;

interface IThorRootService {
    void setAppSuspended(String packageName, boolean suspended);
    void clearAppData(String packageName);
}
