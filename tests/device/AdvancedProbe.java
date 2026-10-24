package dev.display.tests;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;
import dev.posedmcp.plugin.PluginContext;
import dev.posedmcp.plugin.PluginEntry;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Read-only getters and detached AOSP callback fixtures; never starts capture or recording. */
public final class AdvancedProbe implements PluginEntry {
    private Context context;
    private Map<String, Object> baseline;
    private Object recordingOwner, screenshotOwner, screenshotObserver;
    private Consumer<Integer> recordingCallback;
    private Activity.ScreenCaptureCallback screenshotCallback;
    private Activity realActivity;
    private Activity.ScreenCaptureCallback realScreenshot;
    private int realOwnerPid = -1;
    private final AtomicInteger realScreenshots = new AtomicInteger();
    private final AtomicInteger screenshotCount = new AtomicInteger(), recordingCount = new AtomicInteger(), lastRecording = new AtomicInteger(-1);
    public void onLoad(PluginContext context) { this.context = context.appContext(); }
    public Object invoke(String method, String args) throws Exception {
        return switch (method) {
            case "baseline" -> { baseline = getters(); yield new JSONObject().put("savedGetterCount", baseline.size()); }
            case "sample" -> matches(expected());
            case "restored" -> matches(baseline);
            case "captureStart" -> captureStart();
            case "captureEvent" -> { call(recordingOwner, "notifyCallbacks", new Class<?>[]{int.class}, 0);
                call(screenshotObserver, "onScreenCaptured", new Class<?>[0]); Thread.sleep(250); yield captureRead(); }
            case "captureRead" -> captureRead();
            case "captureStop" -> { captureStop(); yield new JSONObject().put("unregistered", true); }
            case "capturePermission" -> permission();
            case "sensors" -> sensors();
            case "realScreenshotStart" -> main(() -> {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                Method current = thread.getDeclaredMethod("currentActivityThread"); current.setAccessible(true);
                Object activityThread = current.invoke(null);
                for (Object record : ((Map<?, ?>)field(activityThread, "mActivities")).values()) {
                    Activity candidate = (Activity)field(record, "activity");
                    if (!candidate.isFinishing() && !candidate.isDestroyed()) { realActivity = candidate; break; }
                }
                if (realActivity == null) throw new IllegalStateException("No live activity");
                realScreenshot = realScreenshots::incrementAndGet;
                realActivity.registerScreenCaptureCallback(context.getMainExecutor(), realScreenshot);
                realOwnerPid = android.os.Process.myPid();
                return new JSONObject().put("realRegistration", true)
                        .put("pid", realOwnerPid)
                        .put("capturePermissionGranted", context.checkSelfPermission("android.permission.DETECT_SCREEN_CAPTURE") == 0);
            });
            case "realScreenshotRead" -> {
                // MCP may try another Chromium process first. Do not report its uninitialized zero.
                if (realOwnerPid < 0) throw new IllegalStateException("No screenshot registration in this process");
                yield new JSONObject().put("events", realScreenshots.get()).put("pid", realOwnerPid);
            }
            case "realScreenshotDuplicate" -> main(() -> {
                boolean rejected = false;
                try { realActivity.registerScreenCaptureCallback(context.getMainExecutor(), realScreenshot); }
                catch (IllegalStateException expected) { rejected = true; }
                return new JSONObject().put("duplicateRegistrationRejected", rejected);
            });
            case "realScreenshotStop" -> main(() -> {
                realActivity.unregisterScreenCaptureCallback(realScreenshot); realScreenshot = null; realActivity = null;
                return new JSONObject().put("unregistered", true);
            });
            default -> throw new IllegalArgumentException("Unknown probe method");
        };
    }
    private static <T> T main(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        return task.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    private JSONObject sensors() throws Exception {
        android.hardware.SensorManager manager = context.getSystemService(android.hardware.SensorManager.class);
        Map<Integer, float[]> expected = new LinkedHashMap<>();
        expected.put(10, new float[]{1.1f, 2.2f, 3.3f}); expected.put(9, new float[]{0, 0, 9.8f});
        expected.put(13, new float[]{19.5f}); expected.put(12, new float[]{42}); expected.put(19, new float[]{1234});
        JSONObject result = new JSONObject();
        for (int type : expected.keySet()) {
            if (manager.getDefaultSensor(type) == null) result.put(String.valueOf(type), "hardware-absent");
            else if (type == 19 && context.checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != 0)
                result.put("19", "permission-not-granted-not-bypassed");
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(expected.size() - result.length());
        android.os.HandlerThread thread = new android.os.HandlerThread("Display-extension-sensors"); thread.start();
        android.hardware.SensorEventListener listener = new android.hardware.SensorEventListener() {
            public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}
            public void onSensorChanged(android.hardware.SensorEvent event) {
                String key = String.valueOf(event.sensor.getType()); float[] test = expected.get(event.sensor.getType());
                synchronized (result) {
                    if (result.has(key)) return;
                    boolean match = event.values.length >= test.length;
                    for (int i = 0; i < test.length && match; i++) match = Math.abs(event.values[i] - test[i]) < .0001f;
                    try { result.put(key, new JSONObject().put("matches", match).put("monotonicTimestamp", event.timestamp > 0
                            && event.timestamp < android.os.SystemClock.elapsedRealtimeNanos() + 5_000_000_000L)); }
                    catch (Exception ignored) {}
                    latch.countDown();
                }
            }
        };
        try {
            for (int type : expected.keySet()) if (!result.has(String.valueOf(type))) {
                boolean registered = manager.registerListener(listener, manager.getDefaultSensor(type), 200_000, new android.os.Handler(thread.getLooper()));
                if (!registered) { result.put(String.valueOf(type), "registration-rejected"); latch.countDown(); }
            }
            latch.await(7, java.util.concurrent.TimeUnit.SECONDS);
            synchronized (result) {
                for (int type : expected.keySet()) if (!result.has(String.valueOf(type))) result.put(String.valueOf(type), "no-event-within-7s");
                return new JSONObject(result.toString());
            }
        } finally { manager.unregisterListener(listener); thread.quitSafely(); }
    }
    @SuppressWarnings("deprecation")
    private Map<String, Object> getters() {
        Map<String, Object> result = new LinkedHashMap<>();
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        result.put("keyguardLocked", keyguard.isKeyguardLocked()); result.put("deviceLocked", keyguard.isDeviceLocked());
        result.put("keyguardSecure", keyguard.isKeyguardSecure()); result.put("deviceSecure", keyguard.isDeviceSecure());
        AudioManager audio = context.getSystemService(AudioManager.class);
        result.put("ringerMode", audio.getRingerMode()); result.put("audioMode", audio.getMode()); result.put("musicActive", audio.isMusicActive());
        result.put("microphoneMute", audio.isMicrophoneMute()); result.put("speakerphoneOn", audio.isSpeakerphoneOn());
        for (int stream : new int[]{3, 4}) {
            int min = audio.getStreamMinVolume(stream), max = audio.getStreamMaxVolume(stream);
            result.put(stream == 3 ? "musicVolume" : "alarmVolume", audio.getStreamVolume(stream));
            result.put(stream == 3 ? "musicMin" : "alarmMin", min); result.put(stream == 3 ? "musicMax" : "alarmMax", max);
        }
        Display display = context.getSystemService(WindowManager.class).getDefaultDisplay();
        result.put("rotation", display.getRotation()); result.put("refreshRate", display.getRefreshRate());
        result.put("modeRefreshRate", display.getMode().getRefreshRate()); result.put("displayState", display.getState());
        PowerManager power = context.getSystemService(PowerManager.class);
        result.put("thermalStatus", power.getCurrentThermalStatus()); result.put("lightIdle", power.isDeviceLightIdleMode());
        result.put("airplaneInt", Settings.Global.getInt(context.getContentResolver(), "airplane_mode_on", -1));
        result.put("airplaneString", Settings.Global.getString(context.getContentResolver(), "airplane_mode_on"));
        result.put("autoRotate", Settings.System.getInt(context.getContentResolver(), "accelerometer_rotation", -1));
        result.put("brightness", Settings.System.getInt(context.getContentResolver(), "screen_brightness", -1));
        result.put("time24", android.text.format.DateFormat.is24HourFormat(context));
        result.put("timeFormat", Settings.System.getString(context.getContentResolver(), "time_12_24"));
        WifiManager wifi = context.getSystemService(WifiManager.class);
        result.put("wifiEnabled", wifi.isWifiEnabled()); result.put("wifiState", wifi.getWifiState());
        // A local data container verifies scalar hooks without reading the connected access point.
        WifiInfo info = new WifiInfo.Builder().build(); result.put("rssi", info.getRssi()); result.put("linkSpeed", info.getLinkSpeed());
        return result;
    }
    private Map<String, Object> expected() {
        Map<String, Object> result = getters();
        result.put("keyguardLocked", true); result.put("deviceLocked", true); result.put("keyguardSecure", true); result.put("deviceSecure", true);
        result.put("ringerMode", 0); result.put("audioMode", 3); result.put("musicActive", true); result.put("microphoneMute", true); result.put("speakerphoneOn", true);
        result.put("musicVolume", (Integer)result.get("musicMin") + Math.round(((Integer)result.get("musicMax") - (Integer)result.get("musicMin")) * .67f));
        result.put("alarmVolume", (Integer)result.get("alarmMin") + Math.round(((Integer)result.get("alarmMax") - (Integer)result.get("alarmMin")) * .23f));
        result.put("rotation", 1); result.put("refreshRate", 75.5f); result.put("modeRefreshRate", 75.5f); result.put("displayState", 3);
        result.put("thermalStatus", 2); result.put("lightIdle", true); result.put("airplaneInt", 1); result.put("airplaneString", "1");
        result.put("autoRotate", 0); result.put("brightness", 77); result.put("time24", true); result.put("timeFormat", "24");
        result.put("wifiEnabled", false); result.put("wifiState", 1); result.put("rssi", -42); result.put("linkSpeed", 866);
        // Stream range getters are reference values, not overridden behavior being asserted.
        for (String key : new String[]{"musicMin", "musicMax", "alarmMin", "alarmMax"}) result.remove(key);
        return result;
    }
    private JSONObject matches(Map<String, Object> expected) throws Exception {
        if (expected == null) throw new IllegalStateException("No baseline");
        Map<String, Object> actual = getters(); JSONObject result = new JSONObject();
        for (var row : expected.entrySet()) result.put(row.getKey(), java.util.Objects.equals(row.getValue(), actual.get(row.getKey())));
        return result;
    }
    private static Object call(Object owner, String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, signature); method.setAccessible(true);
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }
    private static Object instance(String name, Class<?>[] signature, Object... args) throws Exception {
        Constructor<?> constructor = Class.forName(name).getDeclaredConstructor(signature); constructor.setAccessible(true); return constructor.newInstance(args);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    @SuppressWarnings("unchecked")
    private JSONObject captureStart() throws Exception {
        if (recordingOwner != null) throw new IllegalStateException("Already running");
        Executor executor = context.getMainExecutor();
        recordingOwner = instance("android.view.ScreenRecordingCallbacks", new Class<?>[0]);
        // Detached fixture: a sentinel prevents Binder registration/removal. It has no global instance or system state.
        Class<?> notifier = Class.forName("android.window.IScreenRecordingCallback");
        Object stub = Proxy.newProxyInstance(notifier.getClassLoader(), new Class<?>[]{notifier}, (proxy, method, args) -> null);
        Field callbackNotifier = recordingOwner.getClass().getDeclaredField("mCallbackNotifier"); callbackNotifier.setAccessible(true); callbackNotifier.set(recordingOwner, stub);
        Map<Consumer<Integer>, Executor> callbacks = (Map<Consumer<Integer>, Executor>)field(recordingOwner, "mCallbacks");
        callbacks.put(state -> {}, executor);
        recordingCallback = state -> { lastRecording.set(state); recordingCount.incrementAndGet(); };
        int initial = (Integer)call(recordingOwner, "addCallback", new Class<?>[]{Executor.class, Consumer.class}, executor, recordingCallback);
        screenshotOwner = instance("android.app.ScreenCaptureCallbackHandler", new Class<?>[]{IBinder.class}, new Binder());
        Map<Activity.ScreenCaptureCallback, Object> screenshots = (Map<Activity.ScreenCaptureCallback, Object>)field(screenshotOwner, "mScreenCaptureRegistrations");
        Activity.ScreenCaptureCallback sentinel = () -> {};
        Object registration = instance("android.app.ScreenCaptureCallbackHandler$ScreenCaptureRegistration",
                new Class<?>[]{Executor.class, Activity.ScreenCaptureCallback.class}, executor, sentinel);
        screenshots.put(sentinel, registration);
        screenshotCallback = screenshotCount::incrementAndGet;
        call(screenshotOwner, "registerScreenCaptureCallback", new Class<?>[]{Executor.class, Activity.ScreenCaptureCallback.class}, executor, screenshotCallback);
        screenshotObserver = field(screenshotOwner, "mObserver");
        Thread.sleep(250);
        return captureRead().put("initialRecording", initial).put("fixture", "detached-framework-no-binder-registration");
    }
    private JSONObject captureRead() throws Exception {
        return new JSONObject().put("screenshotEvents", screenshotCount.get()).put("recordingEvents", recordingCount.get()).put("lastRecording", lastRecording.get());
    }
    private void captureStop() throws Exception {
        if (recordingOwner != null) call(recordingOwner, "removeCallback", new Class<?>[]{Consumer.class}, recordingCallback);
        if (screenshotOwner != null) call(screenshotOwner, "unregisterScreenCaptureCallback", new Class<?>[]{Activity.ScreenCaptureCallback.class}, screenshotCallback);
        // Keep fixtures alive to allow a post-unregister dispatch test; sentinels have no side effects.
    }
    private JSONObject permission() throws Exception {
        Object owner = instance("android.view.ScreenRecordingCallbacks", new Class<?>[0]);
        boolean denied = false;
        try { call(owner, "addCallback", new Class<?>[]{Executor.class, Consumer.class}, context.getMainExecutor(), (Consumer<Integer>)state -> {}); }
        catch (SecurityException expected) { denied = true; }
        return new JSONObject().put("recordingPermissionNotGranted", context.checkSelfPermission("android.permission.DETECT_SCREEN_RECORDING") != 0)
                .put("recordingPermissionExceptionPreserved", denied);
    }
}
