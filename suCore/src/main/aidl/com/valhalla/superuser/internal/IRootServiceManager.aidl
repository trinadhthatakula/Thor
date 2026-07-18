package com.valhalla.superuser.internal;

import android.content.ComponentName;
import android.content.Intent;

interface IRootServiceManager {
    oneway void broadcast(int uid);
    oneway void stop(in ComponentName name, int uid);
    void connect(in IBinder binder);
    IBinder bind(in Intent intent);
    oneway void unbind(in ComponentName name);
}
