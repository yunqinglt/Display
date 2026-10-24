package dev.display.tests;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Parcel;
import android.location.Location;
import dev.posedmcp.plugin.PluginContext;
import dev.posedmcp.plugin.PluginEntry;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Short-lived read-only device probe. Results contain matches, never ambient sensor samples. */
public final class FrameworkProbe implements PluginEntry {
    private Context context;
    public void onLoad(PluginContext ctx) { context = ctx.appContext(); }
    public Object invoke(String method, String args) throws Exception {
        if (!method.equals("sample")) throw new IllegalArgumentException("sample only");
        long reference = new JSONArray(args).getLong(0);
        boolean active = !new JSONArray(args).optBoolean(1, false);
        JSONObject result = new JSONObject();
        long delta = System.currentTimeMillis() - reference;
        result.put(active ? "wallOffsetApplied" : "wallClockRestored", active
                ? delta > 3_500_000 && delta < 3_750_000 : Math.abs(delta) < 120_000);
        Location location = new Location("gps"); location.setLatitude(0); location.setLongitude(0);
        result.put("locationGetterMatchesExpected", Math.abs(location.getLatitude() - (active ? 35.681236 : 0)) < 0.000001
                && Math.abs(location.getLongitude() - (active ? 139.767125 : 0)) < 0.000001);
        Parcel parcel = Parcel.obtain();
        try {
            location.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            Location decoded = Location.CREATOR.createFromParcel(parcel);
            java.lang.reflect.Field latitude = Location.class.getDeclaredField("mLatitudeDegrees"); latitude.setAccessible(true);
            java.lang.reflect.Field longitude = Location.class.getDeclaredField("mLongitudeDegrees"); longitude.setAccessible(true);
            result.put("locationParcelFieldsMatchExpected", Math.abs(latitude.getDouble(decoded) - (active ? 35.681236 : 0)) < 0.000001
                    && Math.abs(longitude.getDouble(decoded) - (active ? 139.767125 : 0)) < 0.000001);
        } finally { parcel.recycle(); }
        result.put("dateUsesWallClock", Math.abs(new java.util.Date().getTime() - System.currentTimeMillis()) < 1000);
        result.put("instantUsesWallClock", Math.abs(java.time.Instant.now().toEpochMilli() - System.currentTimeMillis()) < 1000);
        long before = SystemClock.elapsedRealtime(); Thread.sleep(30);
        result.put("monotonicUnchanged", SystemClock.elapsedRealtime() - before >= 20 && SystemClock.elapsedRealtime() - before < 3000);
        SensorManager manager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        Map<Integer, float[]> expected = new LinkedHashMap<>();
        expected.put(Sensor.TYPE_ACCELEROMETER, new float[]{1.25f,2.5f,8.75f});
        expected.put(Sensor.TYPE_GYROSCOPE, new float[]{0.1f,0.2f,0.3f});
        expected.put(Sensor.TYPE_MAGNETIC_FIELD, new float[]{11,22,33});
        expected.put(Sensor.TYPE_LIGHT, new float[]{123});
        expected.put(Sensor.TYPE_PRESSURE, new float[]{1001});
        expected.put(Sensor.TYPE_PROXIMITY, new float[]{2});
        JSONObject samples = new JSONObject();
        for (int type : expected.keySet()) if (manager.getDefaultSensor(type) == null) samples.put(String.valueOf(type), "hardware-absent");
        CountDownLatch latch = new CountDownLatch(expected.size() - samples.length());
        HandlerThread worker = new HandlerThread("Display-test-sensors"); worker.start();
        SensorEventListener listener = new SensorEventListener() {
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            public void onSensorChanged(SensorEvent event) {
                int type = event.sensor.getType(); float[] values = expected.get(type);
                synchronized (samples) {
                    if (samples.has(String.valueOf(type))) return;
                    boolean match = event.values.length >= values.length;
                    for (int i = 0; i < values.length && match; i++) match = Math.abs(event.values[i] - values[i]) < 0.0001;
                    try { samples.put(String.valueOf(type), new JSONObject().put(active ? "valueMatches" : "valueDiffersFromTestConfiguration", active ? match : !match)
                            // On-change sensors can legitimately replay an old sample on registration.
                            .put("timestampInMonotonicDomain", event.timestamp > 0
                                    && event.timestamp <= SystemClock.elapsedRealtimeNanos() + 5_000_000_000L)); }
                    catch (Exception ignored) {}
                    latch.countDown();
                }
            }
        };
        try {
            for (int type : expected.keySet()) {
                Sensor sensor = manager.getDefaultSensor(type);
                if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, new Handler(worker.getLooper()));
            }
            result.put("allAvailableSensorsDelivered", latch.await(6, TimeUnit.SECONDS));
        } finally { manager.unregisterListener(listener); worker.quitSafely(); worker.join(1000); }
        synchronized (samples) { result.put("sensors", samples); }
        result.put("listenersRemoved", true);
        return result;
    }
}
