package dev.display;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import dev.display.api.DisplayContract;

/** Authenticate every transaction: a caller can forward a Binder obtained earlier. */
final class CallerPolicy {
    static void enforce(Context context) {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length != 1 || !DisplayContract.CLIENT_PACKAGE.equals(packages[0])
                || pm.checkSignatures(context.getPackageName(), packages[0]) != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Display requires the same-signed dev.posedmcp caller");
    }
    private CallerPolicy() {}
}
