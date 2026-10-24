package dev.display.hooks;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import dev.display.api.DisplayContract;
import io.github.libxposed.api.XposedModule;
import java.util.concurrent.atomic.AtomicBoolean;

/** API 102 native module. Intentionally never installs into system_server or companions. */
public final class DisplayModule extends XposedModule {
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        try {
            String pkg = DisplayContract.target(param.getPackageName());
            if (!installed.compareAndSet(false, true)) return;
            HookEngine engine = new HookEngine(this);
            engine.install();
            hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (started.compareAndSet(false, true)) {
                    engine.prepareLocationCallers(((Context)chain.getArg(0)).getClassLoader());
                    new RuntimeClient((Application)chain.getThisObject(), pkg, engine).start();
                }
                return result;
            });
            log(Log.INFO, "Display", "Framework hooks installed for " + pkg);
        } catch (IllegalArgumentException ignored) { /* excluded package */ }
        catch (Throwable e) { log(Log.ERROR, "Display", "Module initialization failed", e); }
    }
}
