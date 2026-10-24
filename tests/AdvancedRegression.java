package dev.display.tests;

import dev.display.api.DisplayContract;
import dev.display.hooks.CaptureCallbacks;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Callback lifecycle/executor behavior and public input boundaries, without Android mocks. */
public final class AdvancedRegression {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void valid(String key, Object value) throws Exception {
        DisplayContract.validateProfile(new JSONObject().put("schemaVersion", 1).put("id", "advanced")
                .put("rules", new JSONArray().put(new JSONObject().put("key", key).put("value", value))).toString());
    }
    private static void invalid(String key, Object value) throws Exception {
        try { valid(key, value); throw new AssertionError("accepted " + key + "=" + value); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        for (String key : List.of("capture.screenshotEvent", "capture.recordingVisible", "security.deviceLocked", "audio.microphoneMute")) {
            valid(key, false); valid(key, true); checks += 2; invalid(key, 1); invalid(key, "true");
        }
        for (Object[] row : new Object[][]{
                {"audio.ringerMode", 0, 2}, {"audio.mode", 0, 3}, {"audio.musicVolumePercent", 0, 100},
                {"audio.alarmVolumePercent", 0, 100}, {"display.rotation", 0, 3}, {"display.state", 0, 6},
                {"state.thermalStatus", 0, 6}, {"settings.brightness", 0, 255}, {"network.wifiRssi", -127, 0},
                {"network.wifiLinkSpeed", -1, 100000}}) {
            String key = (String)row[0]; int min = (Integer)row[1], max = (Integer)row[2];
            valid(key, min); valid(key, max); checks += 2;
            invalid(key, min - 1); invalid(key, max + 1); invalid(key, 1.5);
        }
        valid("display.refreshRate", 59.94); checks++; invalid("display.refreshRate", 0); invalid("display.refreshRate", 1001);
        valid("sensors.ambientTemperature", new JSONArray().put(-273.15)); checks++;
        invalid("sensors.ambientTemperature", new JSONArray().put(-273.16));
        invalid("sensors.relativeHumidity", new JSONArray().put(-1)); invalid("sensors.relativeHumidity", new JSONArray().put(101));
        invalid("sensors.stepCounter", new JSONArray().put(-1)); invalid("sensors.gravity", new JSONArray().put(1));
        Map<String, Object> rules = new HashMap<>(); List<Throwable> errors = new ArrayList<>();
        CaptureCallbacks registry = new CaptureCallbacks(rules::get, errors::add);
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); Executor executor = jobs::add;
        Object owner = new Object(); List<Integer> seen = new ArrayList<>(); Consumer<Integer> consumer = seen::add;
        Object wrapper = new Object(); var recording = registry.create(owner, consumer, true);
        registry.registered(recording, wrapper, executor, 0);
        check(jobs.isEmpty(), "recording registration uses initial return, no invented callback");
        check(registry.find(owner, consumer, true) == recording && recording.wrapper() == wrapper, "original identity retained");
        check(registry.find(new Object(), consumer, true) == null, "owners are isolated");
        check(recording.recordingValue(1) == 1, "unconfigured callback passes real state");
        rules.put(CaptureCallbacks.RECORDING, false);
        registry.changed(null, null, null, false);
        check(seen.isEmpty() && jobs.size() == 1, "configuration notification uses supplied executor");
        jobs.remove().run(); check(seen.equals(List.of(0)), "forced false callback delivered");
        check(recording.recordingValue(1) == 0, "real event transformed while retaining real state");
        registry.changed(null, null, false, false); check(jobs.isEmpty(), "unrelated revision does not emit recording event");
        rules.remove(CaptureCallbacks.RECORDING); registry.changed(null, null, false, null);
        jobs.remove().run(); check(seen.equals(List.of(0, 1)), "clearing restores most recent real state");
        rules.put(CaptureCallbacks.RECORDING, true); registry.changed(null, null, null, true);
        registry.removed(recording); jobs.remove().run();
        check(seen.size() == 2 && registry.find(owner, consumer, true) == null, "unregister cancels queued callback and removes identity");
        int[] screenshots = {0}; Runnable original = () -> screenshots[0]++; Object screenshotWrapper = new Object();
        var screenshot = registry.create(owner, original, false); screenshot.screenshotDelivery(original);
        rules.put(CaptureCallbacks.SCREENSHOT, true); registry.registered(screenshot, screenshotWrapper, executor, 0);
        check(screenshots[0] == 0 && jobs.size() == 1, "synthetic screenshot is asynchronous on original executor");
        jobs.remove().run(); check(screenshots[0] == 1, "true on registration emits once");
        registry.changed(true, true, null, null); check(jobs.isEmpty(), "unchanged true does not repeat screenshot");
        rules.put(CaptureCallbacks.SCREENSHOT, false); registry.changed(true, false, null, null);
        check(!screenshot.allowScreenshot() && jobs.isEmpty(), "false suppresses real events without synthesizing an event");
        rules.put(CaptureCallbacks.SCREENSHOT, true); registry.changed(false, true, null, null);
        jobs.remove().run(); check(screenshots[0] == 2 && screenshot.allowScreenshot(), "reactivation emits once and permits real events");
        rules.remove(CaptureCallbacks.SCREENSHOT); registry.changed(true, null, null, null);
        check(screenshot.allowScreenshot() && jobs.isEmpty(), "clearing restores screenshot delivery without synthetic event");
        rules.put(CaptureCallbacks.SCREENSHOT, true); registry.changed(null, true, null, null);
        rules.remove(CaptureCallbacks.SCREENSHOT); jobs.remove().run(); check(screenshots[0] == 2, "stale queued screenshot discarded after clear");
        rules.put(CaptureCallbacks.SCREENSHOT, true); registry.changed(null, true, null, null);
        registry.removed(screenshot); jobs.remove().run(); check(screenshots[0] == 2, "unregister cancels pending screenshot");
        var rejected = registry.create(owner, original, false); rejected.screenshotDelivery(original);
        Executor rejecting = task -> { throw new java.util.concurrent.RejectedExecutionException(); };
        Object rejectedWrapper = new Object(); registry.registered(rejected, rejectedWrapper, rejecting, 0);
        check(errors.size() == 1, "executor failure is reported without escaping runtime update");
        rules.put(CaptureCallbacks.RECORDING, false);
        List<Integer> raced = new ArrayList<>(); Consumer<Integer> racedConsumer = raced::add;
        var racing = registry.create(owner, racedConsumer, true); Object racingWrapper = new Object();
        racing.recordingValue(1); // Callback arrives while the original addCallback is returning 0.
        registry.registered(racing, racingWrapper, executor, 0);
        check(racing.recordingInitialValue(0) == 0, "initial result still respects active override");
        rules.remove(CaptureCallbacks.RECORDING); registry.changed(true, true, false, null);
        jobs.remove().run(); check(raced.equals(List.of(1)), "registration completion does not overwrite a newer real event");
        System.out.println("Advanced regression: " + checks + " checks passed");
    }
}
