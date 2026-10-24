package dev.display;

import android.app.Application;
import dev.display.core.AtomicStore;
import dev.display.core.ProfileCore;
import io.github.libxposed.service.XposedServiceHelper;
import java.io.File;

public final class DisplayApplication extends Application {
    public final FrameworkConnection framework = new FrameworkConnection();
    public final RuntimeRegistry runtime = new RuntimeRegistry();
    private ProfileCore core;
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(framework);
    }
    public synchronized ProfileCore core() {
        if (core == null) {
            try { core = new ProfileCore(new AtomicStore(new File(getFilesDir(), "display-state.json")), framework, runtime); }
            catch (Exception e) { throw new IllegalStateException("Display state could not be loaded", e); }
        }
        return core;
    }
}
