package com.aw950aw.smartbackup.shell;

import android.os.ParcelFileDescriptor;

// Runs inside the process that Shizuku starts (normally with shell privileges).
interface IPrivilegedService {
    void destroy() = 16777114;
    int whoAmI() = 1;
    int run(String command, in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr) = 2;
}
