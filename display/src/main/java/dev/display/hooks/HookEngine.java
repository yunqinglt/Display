package dev.display.hooks;

import android.app.Application;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.location.Location;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.LocaleList;
import android.util.SparseArray;
import io.github.libxposed.api.XposedInterface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Local immutable rule snapshots. No network, storage or Binder inside hooked methods. */
final class HookEngine {
    private record Snapshot(long revision, JSONObject values) {}
    private volatile Snapshot snapshot = new Snapshot(0, new JSONObject());
    private final XposedInterface framework;
    private final Set<String> supported = ConcurrentHashMap.newKeySet();
    private final Map<String, LongAdder> hits = new ConcurrentHashMap<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final java.util.List<XposedInterface.HookHandle> handles = new java.util.ArrayList<>();
    private XposedInterface.Invoker<?, Method> originalWall;
    private CaptureHooks capture;
    private static final Object ORIGINAL = new Object();
    interface After { Object apply(XposedInterface.Chain chain, Object result) throws Exception; }
    HookEngine(XposedInterface framework) { this.framework = framework; }
    /** ART can inline boot-class getters into app callers before module injection. */
    void prepareLocationCallers(ClassLoader loader) {
        try {
            Class<?> adapter = Class.forName("org.chromium.device.geolocation.LocationProviderAdapter", false, loader);
            for (Method method : adapter.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                // Match the signature as release builds can rename onNewLocationAvailable.
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && parameters.length >= 1 && parameters[0] == Location.class) {
                    if (!framework.deoptimize(method)) failed.add("location-chromium-caller");
                }
            }
        } catch (ClassNotFoundException ignored) { /* optional Chromium compatibility */ }
        catch (Throwable e) { failed.add("location-chromium-caller"); }
    }
    private Object rule(String key) {
        Object result = snapshot.values.opt(key);
        if (result != null) hits.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        return result;
    }
    private boolean after(String owner, String name, After callback) {
        int count = 0;
        try {
            for (Method method : Class.forName(owner).getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                try {
                    method.setAccessible(true);
                    handles.add(framework.hook(method).intercept(chain -> {
                        Object result = chain.proceed(); // preserve original permissions and exceptions
                        try { Object value = callback.apply(chain, result); return value == ORIGINAL ? result : value; }
                        catch (Exception e) { failed.add(owner + "#" + name); return result; }
                    }));
                    count++;
                } catch (Throwable e) { failed.add(owner + "#" + name); }
            }
        } catch (Throwable e) { failed.add(owner + "#" + name); }
        return count > 0;
    }
    private void simple(String key, String owner, String name) {
        if (after(owner, name, (chain, original) -> {
            Object value = rule(key); if (value == null) return ORIGINAL;
            // JSON integers may be Long; ART requires the exact primitive wrapper of the method.
            if (value instanceof Number n) {
                if (original instanceof Integer) return n.intValue();
                if (original instanceof Long) return n.longValue();
                if (original instanceof Float) return n.floatValue();
                if (original instanceof Double) return n.doubleValue();
            }
            return value;
        })) supported.add(key);
    }
    void install() {
        try {
            originalWall = framework.getInvoker(System.class.getDeclaredMethod("currentTimeMillis"))
                    .setType(XposedInterface.Invoker.Type.ORIGIN);
            handles.add(framework.hook(java.util.Date.class.getDeclaredConstructor()).intercept(chain -> {
                Object result = chain.proceed();
                if (hasTime()) {
                    try { ((java.util.Date)chain.getThisObject()).setTime(wall(((Number)originalWall.invoke(null)).longValue())); }
                    catch (Exception e) { failed.add("date-constructor"); }
                }
                return result;
            }));
        } catch (Throwable e) { failed.add("date-constructor"); }
        boolean wall = after("java.lang.System", "currentTimeMillis", (chain, original) -> wall(((Number)original).longValue()));
        // Date/Instant have optimized/native clock callers which may bypass the System hook.
        after("java.time.Clock", "currentInstant", (chain, original) -> {
            if (!hasTime()) return ORIGINAL;
            return originalWall == null ? ORIGINAL : java.time.Instant.ofEpochMilli(wall(((Number)originalWall.invoke(null)).longValue()));
        });
        if (wall) supported.addAll(Arrays.asList("time.wallOffsetMillis", "time.wallFixedMillis"));
        simple("state.powerSave", "android.os.PowerManager", "isPowerSaveMode");
        simple("state.interactive", "android.os.PowerManager", "isInteractive");
        simple("state.deviceIdle", "android.os.PowerManager", "isDeviceIdleMode");
        installLocale(); installNetwork(); installBattery(); installSensors(); installLocation(); installExtended();
        capture = new CaptureHooks(framework, this::rule, supported, failed, handles);
        capture.install();
    }
    private void installExtended() {
        for (String[] entry : new String[][]{
                {"security.keyguardLocked", "android.app.KeyguardManager", "isKeyguardLocked"},
                {"security.deviceLocked", "android.app.KeyguardManager", "isDeviceLocked"},
                {"security.keyguardSecure", "android.app.KeyguardManager", "isKeyguardSecure"},
                {"security.deviceSecure", "android.app.KeyguardManager", "isDeviceSecure"},
                {"audio.ringerMode", "android.media.AudioManager", "getRingerMode"},
                {"audio.mode", "android.media.AudioManager", "getMode"},
                {"audio.musicActive", "android.media.AudioManager", "isMusicActive"},
                {"audio.microphoneMute", "android.media.AudioManager", "isMicrophoneMute"},
                {"audio.speakerphoneOn", "android.media.AudioManager", "isSpeakerphoneOn"},
                {"display.rotation", "android.view.Display", "getRotation"},
                {"display.refreshRate", "android.view.Display", "getRefreshRate"},
                {"display.refreshRate", "android.view.Display$Mode", "getRefreshRate"},
                {"display.state", "android.view.Display", "getState"},
                {"state.thermalStatus", "android.os.PowerManager", "getCurrentThermalStatus"},
                {"network.wifiRssi", "android.net.wifi.WifiInfo", "getRssi"},
                {"network.wifiLinkSpeed", "android.net.wifi.WifiInfo", "getLinkSpeed"},
                {"network.wifiEnabled", "android.net.wifi.WifiManager", "isWifiEnabled"},
                {"settings.time24Hour", "android.text.format.DateFormat", "is24HourFormat"}}) simple(entry[0], entry[1], entry[2]);
        if (android.os.Build.VERSION.SDK_INT >= 33) simple("state.lightDeviceIdle", "android.os.PowerManager", "isDeviceLightIdleMode");
        after("android.net.wifi.WifiManager", "getWifiState", (c, r) -> {
            Object v = rule("network.wifiEnabled"); return v == null ? ORIGINAL : (Boolean)v ? 3 : 1;
        });
        if (after("android.media.AudioManager", "getStreamVolume", (c, r) -> {
            int stream = (Integer)c.getArg(0);
            Object value = rule(stream == 3 ? "audio.musicVolumePercent" : stream == 4 ? "audio.alarmVolumePercent" : "");
            if (value == null) return ORIGINAL;
            android.media.AudioManager manager = (android.media.AudioManager)c.getThisObject();
            int min = manager.getStreamMinVolume(stream), max = manager.getStreamMaxVolume(stream);
            return min + Math.round(((Number)value).intValue() * (max - min) / 100f);
        })) supported.addAll(Set.of("audio.musicVolumePercent", "audio.alarmVolumePercent"));
        settings("android.provider.Settings$Global", Map.of("airplane_mode_on", "settings.airplaneMode"));
        settings("android.provider.Settings$System", Map.of("accelerometer_rotation", "settings.autoRotate",
                "screen_brightness", "settings.brightness", "time_12_24", "settings.time24Hour"));
    }
    private void settings(String owner, Map<String, String> names) {
        // getInt/Long/Float and their ForUser overloads parse the framework's string read.
        if (after(owner, "getStringForUser", (c, r) -> {
            String key = names.get((String)c.getArg(1)); if (key == null) return ORIGINAL;
            Object v = rule(key); if (v == null) return ORIGINAL;
            if (key.equals("settings.time24Hour")) return (Boolean)v ? "24" : "12";
            return v instanceof Boolean ? ((Boolean)v ? "1" : "0") : v.toString();
        })) supported.addAll(names.values());
    }
    private boolean hasTime() { return snapshot.values.has("time.wallFixedMillis") || snapshot.values.has("time.wallOffsetMillis"); }
    private long wall(long original) {
        Object fixed = rule("time.wallFixedMillis"); if (fixed != null) return ((Number)fixed).longValue();
        Object offset = rule("time.wallOffsetMillis"); return offset == null ? original : Math.max(0, original + ((Number)offset).longValue());
    }
    private LocaleList locales() throws Exception {
        Object value = rule("locale.languageTags"); if (value == null) return null;
        JSONArray a = (JSONArray)value; Locale[] result = new Locale[a.length()];
        for (int i = 0; i < result.length; i++) result[i] = Locale.forLanguageTag(a.getString(i));
        return new LocaleList(result);
    }
    private void installLocale() {
        boolean language = after("java.util.Locale", "getDefault", (chain, original) -> {
            LocaleList list = locales(); return list == null ? ORIGINAL : list.get(0);
        });
        after("android.os.LocaleList", "getDefault", (chain, original) -> { LocaleList list = locales(); return list == null ? ORIGINAL : list; });
        after("android.os.LocaleList", "getAdjustedDefault", (chain, original) -> { LocaleList list = locales(); return list == null ? ORIGINAL : list; });
        after("android.content.res.Resources", "getConfiguration", (chain, original) -> {
            LocaleList list = locales(); if (list == null) return ORIGINAL;
            Configuration copy = new Configuration((Configuration)original); copy.setLocales(list); return copy;
        });
        if (language) supported.add("locale.languageTags");
        if (after("java.util.TimeZone", "getDefault", (chain, original) -> {
            Object value = rule("locale.timeZone"); return value == null ? ORIGINAL : TimeZone.getTimeZone(java.time.ZoneId.of((String)value));
        })) supported.add("locale.timeZone");
    }
    private static int transport(String value) {
        return switch (value) { case "wifi" -> 1; case "cellular" -> 0; case "ethernet" -> 3; case "vpn" -> 4; default -> -1; };
    }
    private void installNetwork() {
        simple("network.metered", "android.net.ConnectivityManager", "isActiveNetworkMetered");
        if (after("android.net.NetworkInfo", "isConnected", (c, r) -> { Object v = rule("network.connected"); return v == null ? ORIGINAL : v; })) supported.add("network.connected");
        after("android.net.NetworkInfo", "isConnectedOrConnecting", (c, r) -> { Object v = rule("network.connected"); return v == null ? ORIGINAL : v; });
        after("android.net.NetworkInfo", "getState", (c, r) -> { Object v = rule("network.connected"); return v == null ? ORIGINAL : (Boolean)v ? android.net.NetworkInfo.State.CONNECTED : android.net.NetworkInfo.State.DISCONNECTED; });
        after("android.net.NetworkInfo", "getDetailedState", (c, r) -> { Object v = rule("network.connected"); return v == null ? ORIGINAL : (Boolean)v ? android.net.NetworkInfo.DetailedState.CONNECTED : android.net.NetworkInfo.DetailedState.DISCONNECTED; });
        after("android.net.ConnectivityManager", "getActiveNetwork", (c, r) -> Boolean.FALSE.equals(rule("network.connected")) ? null : ORIGINAL);
        if (after("android.net.NetworkCapabilities", "hasCapability", (c, r) -> {
            int cap = (Integer)c.getArg(0); Object value = null;
            if (cap == NetworkCapabilities.NET_CAPABILITY_VALIDATED) value = rule("network.validated");
            if (cap == NetworkCapabilities.NET_CAPABILITY_INTERNET) value = rule("network.connected");
            if (cap == NetworkCapabilities.NET_CAPABILITY_NOT_METERED) { Object metered = rule("network.metered"); value = metered == null ? null : !(Boolean)metered; }
            return value == null ? ORIGINAL : value;
        })) supported.add("network.validated");
        if (after("android.net.NetworkCapabilities", "hasTransport", (c, r) -> {
            Object value = rule("network.transport"); return value == null ? ORIGINAL : (Integer)c.getArg(0) == transport((String)value);
        })) supported.add("network.transport");
        after("android.net.NetworkCapabilities", "getTransportTypes", (c, r) -> {
            Object value = rule("network.transport"); if (value == null) return ORIGINAL;
            int t = transport((String)value); return t < 0 ? new int[0] : new int[]{t};
        });
        after("android.net.NetworkCapabilities", "getCapabilities", (c, r) -> {
            java.util.TreeSet<Integer> caps = new java.util.TreeSet<>(); for (int x : (int[])r) caps.add(x);
            adjust(caps, NetworkCapabilities.NET_CAPABILITY_VALIDATED, rule("network.validated"), false);
            adjust(caps, NetworkCapabilities.NET_CAPABILITY_INTERNET, rule("network.connected"), false);
            adjust(caps, NetworkCapabilities.NET_CAPABILITY_NOT_METERED, rule("network.metered"), true);
            return caps.stream().mapToInt(Integer::intValue).toArray();
        });
        after("android.net.NetworkInfo", "getType", (c, r) -> { Object v = rule("network.transport"); return v == null ? ORIGINAL : switch ((String)v) { case "wifi" -> 1; case "cellular" -> 0; case "ethernet" -> 9; case "vpn" -> 17; default -> -1; }; });
        after("android.net.NetworkInfo", "getTypeName", (c, r) -> { Object v = rule("network.transport"); return v == null ? ORIGINAL : ((String)v).toUpperCase(Locale.ROOT); });
    }
    private static void adjust(Set<Integer> caps, int key, Object value, boolean invert) {
        if (value == null) return; if ((Boolean)value != invert) caps.add(key); else caps.remove(key);
    }
    private void installBattery() {
        if (after("android.os.BatteryManager", "getIntProperty", (c, r) -> {
            int property = (Integer)c.getArg(0); String key = property == BatteryManager.BATTERY_PROPERTY_CAPACITY ? "state.batteryLevel"
                    : property == BatteryManager.BATTERY_PROPERTY_STATUS ? "state.batteryStatus" : "";
            Object v = rule(key); return v == null ? ORIGINAL : ((Number)v).intValue();
        })) supported.addAll(Arrays.asList("state.batteryLevel", "state.batteryStatus"));
        after("android.os.BatteryManager", "getLongProperty", (c, r) -> {
            int property = (Integer)c.getArg(0); Object v = rule(property == BatteryManager.BATTERY_PROPERTY_CAPACITY ? "state.batteryLevel"
                    : property == BatteryManager.BATTERY_PROPERTY_STATUS ? "state.batteryStatus" : "");
            return v == null ? ORIGINAL : ((Number)v).longValue();
        });
        after("android.os.BatteryManager", "isCharging", (c, r) -> {
            Object v = rule("state.batteryStatus"); return v == null ? ORIGINAL : ((Number)v).intValue() == 2 || ((Number)v).intValue() == 5;
        });
        // Both sticky reads and subsequently delivered ACTION_BATTERY_CHANGED use Intent extras.
        if (after("android.content.Intent", "getIntExtra", (c, r) -> {
            Intent intent = (Intent)c.getThisObject(); if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return ORIGINAL;
            String extra = (String)c.getArg(0), key = switch (extra) {
                case "level" -> "state.batteryLevel"; case "status" -> "state.batteryStatus"; case "plugged" -> "state.batteryPlugged"; default -> ""; };
            Object value = rule(key);
            if (extra.equals("scale") && snapshot.values.has("state.batteryLevel")) return 100;
            return value == null ? ORIGINAL : ((Number)value).intValue();
        })) supported.add("state.batteryPlugged");
        after("android.content.Intent", "getExtras", (c, r) -> {
            if (!Intent.ACTION_BATTERY_CHANGED.equals(((Intent)c.getThisObject()).getAction()) || r == null) return ORIGINAL;
            android.os.Bundle copy = new android.os.Bundle((android.os.Bundle)r);
            for (String[] pair : new String[][]{{"level", "state.batteryLevel"}, {"status", "state.batteryStatus"}, {"plugged", "state.batteryPlugged"}}) {
                Object v = rule(pair[1]); if (v != null) { copy.putInt(pair[0], ((Number)v).intValue()); if (pair[0].equals("level")) copy.putInt("scale", 100); }
            }
            return copy;
        });
    }
    private static String sensorKey(int type) {
        return switch (type) { case Sensor.TYPE_ACCELEROMETER -> "sensors.accelerometer"; case Sensor.TYPE_GYROSCOPE -> "sensors.gyroscope";
            case Sensor.TYPE_MAGNETIC_FIELD -> "sensors.magneticField"; case Sensor.TYPE_LIGHT -> "sensors.light";
            case Sensor.TYPE_PRESSURE -> "sensors.pressure"; case Sensor.TYPE_PROXIMITY -> "sensors.proximity";
            case Sensor.TYPE_LINEAR_ACCELERATION -> "sensors.linearAcceleration"; case Sensor.TYPE_GRAVITY -> "sensors.gravity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE -> "sensors.ambientTemperature"; case Sensor.TYPE_RELATIVE_HUMIDITY -> "sensors.relativeHumidity";
            case Sensor.TYPE_STEP_COUNTER -> "sensors.stepCounter"; default -> ""; };
    }
    private static final Set<String> SENSOR_RULES = Set.of("sensors.accelerometer", "sensors.gyroscope", "sensors.magneticField",
            "sensors.light", "sensors.pressure", "sensors.proximity", "sensors.linearAcceleration", "sensors.gravity",
            "sensors.ambientTemperature", "sensors.relativeHumidity", "sensors.stepCounter");
    // Framework-injected code deliberately uses Android internals. Unsupported layouts are reported.
    @android.annotation.SuppressLint({"PrivateApi", "SoonBlockedPrivateApi"})
    private void installSensors() {
        try {
            Class<?> queue = Class.forName("android.hardware.SystemSensorManager$SensorEventQueue");
            Field manager = queue.getSuperclass().getDeclaredField("mManager"); manager.setAccessible(true);
            Field sensors = Class.forName("android.hardware.SystemSensorManager").getDeclaredField("mHandleToSensor"); sensors.setAccessible(true);
            Method dispatch = queue.getDeclaredMethod("dispatchSensorEvent", int.class, float[].class, int.class, long.class);
            dispatch.setAccessible(true);
            handles.add(framework.hook(dispatch).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                try {
                    Object table = sensors.get(manager.get(chain.getThisObject()));
                    Sensor sensor = table instanceof Map ? (Sensor)((Map<?, ?>)table).get(args[0])
                            : (Sensor)((SparseArray<?>)table).get((Integer)args[0]);
                    if (sensor != null) {
                        JSONArray v = (JSONArray)rule(sensorKey(sensor.getType()));
                        if (v != null) { float[] copy = ((float[])args[1]).clone(); for (int i = 0; i < Math.min(v.length(), copy.length); i++) copy[i] = (float)v.getDouble(i); args[1] = copy; }
                    }
                } catch (Exception e) {
                    failed.add("sensor-dispatch");
                    supported.removeAll(SENSOR_RULES);
                }
                return chain.proceed(args);
            }));
            supported.addAll(SENSOR_RULES);
        } catch (Throwable e) { failed.add("sensor-dispatch"); }
    }
    private void installLocation() {
        boolean latitude = after("android.location.Location", "getLatitude", (c, r) -> {
            JSONObject v = (JSONObject)rule("location.position"); return v == null ? ORIGINAL : v.getDouble("latitude");
        });
        boolean longitude = after("android.location.Location", "getLongitude", (c, r) -> {
            JSONObject v = (JSONObject)rule("location.position"); return v == null ? ORIGINAL : v.getDouble("longitude");
        });
        boolean accuracy = after("android.location.Location", "getAccuracy", (c, r) -> {
            JSONObject v = (JSONObject)rule("location.position"); return v == null ? ORIGINAL : (float)v.getDouble("accuracyMeters");
        });
        after("android.location.Location", "hasAccuracy", (c, r) -> snapshot.values.has("location.position") ? true : ORIGINAL);
        // Compiled/JNI consumers can inline getters. Change copies at IPC delivery boundaries too.
        after("android.location.LocationManager", "getLastKnownLocation", (c, r) -> r instanceof Location ? locationCopy((Location)r) : ORIGINAL);
        after(Location.CREATOR.getClass().getName(), "createFromParcel", (c, r) -> r instanceof Location ? locationCopy((Location)r) : ORIGINAL);
        locationTransport("android.location.LocationManager$GetCurrentLocationTransport", "onLocation");
        locationTransport("android.location.LocationManager$LocationListenerTransport", "onLocationChanged");
        if (latitude && longitude && accuracy) supported.add("location.position");
    }
    private Location locationCopy(Location source) throws Exception {
        JSONObject value = (JSONObject)rule("location.position"); if (value == null) return source;
        Location copy = new Location(source);
        copy.setLatitude(value.getDouble("latitude")); copy.setLongitude(value.getDouble("longitude"));
        copy.setAccuracy((float)value.getDouble("accuracyMeters"));
        return copy;
    }
    @android.annotation.SuppressLint("PrivateApi")
    private void locationTransport(String owner, String name) {
        try {
            for (Method method : Class.forName(owner).getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                method.setAccessible(true);
                handles.add(framework.hook(method).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (snapshot.values.has("location.position")) {
                        try {
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof Location) args[i] = locationCopy((Location)args[i]);
                                else if (args[i] instanceof java.util.List<?>) {
                                    java.util.List<?> list = (java.util.List<?>)args[i];
                                    java.util.List<Object> copy = new java.util.ArrayList<>(list.size());
                                    for (Object item : list) copy.add(item instanceof Location ? locationCopy((Location)item) : item);
                                    args[i] = copy;
                                }
                            }
                        } catch (Exception e) { failed.add("location-delivery"); }
                    }
                    return chain.proceed(args);
                }));
            }
        } catch (Throwable e) { failed.add("location-delivery"); }
    }
    void update(JSONObject incoming) throws Exception {
        long revision = incoming.getLong("revision"); if (revision == snapshot.revision) return;
        JSONObject values = new JSONObject(); JSONArray rules = incoming.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) { JSONObject r = rules.getJSONObject(i); values.put(r.getString("key"), r.get("value")); }
        Snapshot previous = snapshot;
        snapshot = new Snapshot(revision, values); hits.clear();
        if (capture != null) capture.changed(previous.values, values);
    }
    void reset() {
        Snapshot previous = snapshot; snapshot = new Snapshot(0, new JSONObject()); hits.clear();
        if (capture != null) capture.changed(previous.values, snapshot.values);
    }
    JSONObject report() throws Exception {
        Snapshot current = snapshot; JSONArray unsupported = new JSONArray();
        current.values.keys().forEachRemaining(key -> { if (!supported.contains(key)) unsupported.put(key); });
        JSONObject observations = new JSONObject();
        for (Map.Entry<String, LongAdder> entry : hits.entrySet()) observations.put(entry.getKey(), new JSONObject().put("calls", entry.getValue().sum()));
        return new JSONObject().put("ackRevision", current.revision).put("supportedRules", new JSONArray(supported))
                .put("unsupportedRules", unsupported).put("observations", observations).put("hookFailures", new JSONArray(failed));
    }
}
