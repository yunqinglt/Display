package dev.display;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import dev.display.api.DisplayContract;
import dev.display.api.IDisplayRuntime;
import dev.display.core.CoreException;
import org.json.JSONObject;

public final class DisplayRuntimeService extends Service {
    private final IDisplayRuntime.Stub binder = new IDisplayRuntime.Stub() {
        public String exchange(String pkg, String process, String session, String raw) {
            try {
                DisplayContract.target(pkg); DisplayContract.id(session);
                int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
                String[] packages = getPackageManager().getPackagesForUid(uid);
                // Shared-UID packages cannot impersonate one another here.
                if (packages == null || packages.length != 1 || !packages[0].equals(pkg)
                        || uid / 100000 != android.os.Process.myUid() / 100000)
                    throw new SecurityException("Runtime package does not uniquely belong to caller UID");
                DisplayApplication app = (DisplayApplication)getApplication();
                if (!app.framework.contains(pkg)) throw new SecurityException("Caller is outside Display's framework scope");
                JSONObject report = DisplayContract.parse(raw);
                app.runtime.report(pkg, pid, process, session, report);
                return DisplayContract.ok(app.core().snapshot(pkg)).toString();
            } catch (SecurityException e) { return DisplayContract.error("UNAUTHORIZED", e.getMessage()).toString(); }
            catch (CoreException e) { return DisplayContract.error(e.code, e.getMessage()).toString(); }
            catch (Exception e) { return DisplayContract.error("RUNTIME_ERROR", "Runtime exchange failed").toString(); }
        }
    };
    public IBinder onBind(Intent intent) { return binder; }
}
