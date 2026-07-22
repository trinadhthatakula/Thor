package com.valhalla.thor.rootservice;

interface IThorRootService {
    boolean setAppSuspended(String packageName, boolean suspended);
    boolean clearAppData(String packageName);
}
