package com.nomad.droid.shizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface INomadPrivilegedService {
    void destroy() = 16777114;

    int getUid() = 1;
    Bundle getCapabilities() = 2;
    Bundle installPackage(in ParcelFileDescriptor apk, String expectedSha256, boolean replace) = 3;
    Bundle inspectPackage(String packageName) = 4;
    Bundle inspectService(String packageName, String componentName) = 5;
    Bundle startService(String packageName, String componentName) = 6;
    Bundle stopService(String packageName, String componentName) = 7;
    Bundle forceStopPackage(String packageName) = 8;
}

