package dev.display.hooks;

import android.app.Activity;
import android.os.Build;
import io.github.libxposed.api.XposedInterface;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Android 14/15 registration wrappers. The system still checks permissions and owns registration. */
final class CaptureHooks {
    private final XposedInterface framework;
    private final Set<String> supported, failed;
    private final List<XposedInterface.HookHandle> handles;
    private final CaptureCallbacks callbacks;
    CaptureHooks(XposedInterface framework, Function<String, Object> rules, Set<String> supported,
                 Set<String> failed, List<XposedInterface.HookHandle> handles) {
        this.framework = framework; this.supported = supported; this.failed = failed; this.handles = handles;
        callbacks = new CaptureCallbacks(rules, error -> failed.add("capture-callback-delivery"));
    }
    void install() {
        if (Build.VERSION.SDK_INT >= 34) installScreenshot();
        if (Build.VERSION.SDK_INT >= 35) installRecording();
    }
    @android.annotation.SuppressLint("PrivateApi")
    private void installScreenshot() {
        if (Build.VERSION.SDK_INT < 34) return;
        XposedInterface.HookHandle registration = null;
        try {
            Class<?> owner = Class.forName("android.app.ScreenCaptureCallbackHandler");
            Method add = owner.getDeclaredMethod("registerScreenCaptureCallback", Executor.class, Activity.ScreenCaptureCallback.class);
            Method remove = owner.getDeclaredMethod("unregisterScreenCaptureCallback", Activity.ScreenCaptureCallback.class);
            add.setAccessible(true); remove.setAccessible(true);
            registration = framework.hook(add).intercept(chain -> {
                Executor executor = (Executor)chain.getArg(0);
                Activity.ScreenCaptureCallback original = (Activity.ScreenCaptureCallback)chain.getArg(1);
                // Preserve original null checks and duplicate registration errors.
                if (executor == null || original == null) return chain.proceed();
                CaptureCallbacks.Entry previous = callbacks.find(chain.getThisObject(), original, false);
                CaptureCallbacks.Entry entry = previous == null ? callbacks.create(chain.getThisObject(), original, false) : previous;
                Runnable delivery = original::onScreenCaptured;
                Activity.ScreenCaptureCallback wrapped = previous == null ? () -> {
                    if (entry.allowScreenshot()) delivery.run();
                } : (Activity.ScreenCaptureCallback)previous.wrapper();
                Object result = chain.proceed(new Object[]{executor, wrapped});
                entry.screenshotDelivery(delivery);
                callbacks.registered(entry, wrapped, executor, 0);
                return result;
            });
            XposedInterface.HookHandle removal = framework.hook(remove).intercept(chain -> {
                CaptureCallbacks.Entry entry = callbacks.find(chain.getThisObject(), chain.getArg(0), false);
                Object result = entry == null ? chain.proceed() : chain.proceed(new Object[]{entry.wrapper()});
                callbacks.removed(entry); return result;
            });
            handles.add(registration); handles.add(removal); supported.add(CaptureCallbacks.SCREENSHOT);
        } catch (Throwable error) {
            if (registration != null) registration.unhook();
            failed.add("capture-screenshot-registration");
        }
    }
    @android.annotation.SuppressLint("PrivateApi")
    @SuppressWarnings("unchecked")
    private void installRecording() {
        XposedInterface.HookHandle registration = null;
        try {
            Class<?> owner = Class.forName("android.view.ScreenRecordingCallbacks");
            Method add = owner.getDeclaredMethod("addCallback", Executor.class, Consumer.class);
            Method remove = owner.getDeclaredMethod("removeCallback", Consumer.class);
            add.setAccessible(true); remove.setAccessible(true);
            registration = framework.hook(add).intercept(chain -> {
                Executor executor = (Executor)chain.getArg(0); Consumer<Integer> original = (Consumer<Integer>)chain.getArg(1);
                if (executor == null || original == null) return chain.proceed();
                CaptureCallbacks.Entry previous = callbacks.find(chain.getThisObject(), original, true);
                CaptureCallbacks.Entry entry = previous == null ? callbacks.create(chain.getThisObject(), original, true) : previous;
                Consumer<Integer> wrapped = previous == null ? state -> original.accept(entry.recordingValue(state))
                        : (Consumer<Integer>)previous.wrapper();
                // Only commit after a successful registration; SecurityException is propagated unchanged.
                int realState = (Integer)chain.proceed(new Object[]{executor, wrapped});
                callbacks.registered(entry, wrapped, executor, realState);
                return entry.recordingInitialValue(realState);
            });
            XposedInterface.HookHandle removal = framework.hook(remove).intercept(chain -> {
                CaptureCallbacks.Entry entry = callbacks.find(chain.getThisObject(), chain.getArg(0), true);
                Object result = entry == null ? chain.proceed() : chain.proceed(new Object[]{entry.wrapper()});
                callbacks.removed(entry); return result;
            });
            handles.add(registration); handles.add(removal); supported.add(CaptureCallbacks.RECORDING);
        } catch (Throwable error) {
            if (registration != null) registration.unhook();
            failed.add("capture-recording-registration");
        }
    }
    void changed(JSONObject previous, JSONObject current) {
        callbacks.changed(previous.opt(CaptureCallbacks.SCREENSHOT), current.opt(CaptureCallbacks.SCREENSHOT),
                previous.opt(CaptureCallbacks.RECORDING), current.opt(CaptureCallbacks.RECORDING));
    }
}
