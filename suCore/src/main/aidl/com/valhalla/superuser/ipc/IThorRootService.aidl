package com.valhalla.superuser.ipc;

interface IThorRootService {
    void setAppSuspended(String packageName, boolean suspended);
    void clearAppData(String packageName);
}
