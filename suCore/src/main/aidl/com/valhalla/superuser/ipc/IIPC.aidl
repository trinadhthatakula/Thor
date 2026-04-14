package com.valhalla.superuser.ipc;

interface IIPC {
    IBinder getService(String name);
}
