package dev.display.tests;

import dev.display.DisplayEndpoint;
import dev.display.api.DisplayContract;
import dev.display.core.DisplayCore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Host regression: protocol boundaries and no false Core success; no Android mocks. */
public final class FrontendRegression {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static JSONObject rule(String key, Object value) throws Exception {
        return new JSONObject().put("key", key).put("value", value);
    }
    private static String profile(JSONObject... rules) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("id", "demo")
                .put("rules", new JSONArray(List.of(rules))).toString();
    }
    private static void invalid(String input) throws Exception {
        try { DisplayContract.validateProfile(input); throw new AssertionError("accepted invalid: " + input); }
        catch (IllegalArgumentException | org.json.JSONException expected) { checks++; }
    }
    private static void code(String raw, String code) throws Exception {
        JSONObject r = new JSONObject(raw);
        check(!r.getBoolean("ok") && r.getJSONObject("error").getString("code").equals(code), raw);
    }
    public static void main(String[] args) throws Exception {
        DisplayEndpoint absent = new DisplayEndpoint(null);
        code(DisplayContract.decodeReply("{}").toString(), "INVALID_REPLY");
        code(DisplayContract.decodeReply(null).toString(), "INVALID_REPLY");
        code(DisplayContract.decodeReply("{\"apiVersion\":2,\"ok\":true,\"data\":{}}").toString(), "VERSION_MISMATCH");
        code(DisplayContract.decodeReply("{\"apiVersion\":\"1\",\"ok\":true,\"data\":{}}").toString(), "INVALID_REPLY");
        code(DisplayContract.decodeReply("{\"apiVersion\":1,\"ok\":\"true\",\"data\":{}}").toString(), "INVALID_REPLY");
        code(DisplayContract.decodeReply("{\"apiVersion\":1,\"ok\":false,\"error\":{}}").toString(), "INVALID_REPLY");
        check(DisplayContract.decodeReply(absent.reply(absent::ping)).getBoolean("ok"), "valid reply");
        check(new JSONObject(absent.reply(absent::status)).getJSONObject("data").getBoolean("coreAvailable") == false, "Core absent");
        JSONArray catalogue = DisplayContract.capabilities().getJSONArray("rules");
        check(catalogue.length() == 51, "catalogue count");
        for (int i = 0; i < catalogue.length(); i++) check(!catalogue.getJSONObject(i).getBoolean("runtimeSupported"), "no runtime claim");
        String valid = profile(rule("state.batteryLevel", 80), rule("locale.timeZone", "Asia/Shanghai"),
                rule("sensors.accelerometer", new JSONArray("[0,0,9.80665]")));
        JSONObject v = new JSONObject(absent.reply(() -> absent.validate(valid))).getJSONObject("data");
        check(v.getBoolean("valid") && !v.getBoolean("persisted") && !v.getBoolean("applied") && !v.getBoolean("runtimeVerified"), "validation only");
        code(absent.reply(() -> absent.getProfile("demo")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.putProfile(valid, 0, "req-1")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.deleteProfile("demo", 1, "req-2")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.getTarget("com.example.app")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.apply("com.example.app", "demo", 1, 0, "req-3")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.clear("com.example.app", 1, "req-4")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.runtime("com.example.app")), "CORE_UNAVAILABLE");
        code(absent.reply(() -> absent.getProfile("../bad")), "INVALID_ARGUMENT");
        code(absent.reply(() -> absent.apply("android", "demo", 1, 0, "req-5")), "INVALID_ARGUMENT");
        code(absent.reply(() -> absent.apply("com.example.app", "demo", 0, 0, "req-5")), "INVALID_ARGUMENT");
        code(absent.reply(() -> absent.putProfile(valid, -1, "req-5")), "INVALID_ARGUMENT");
        invalid(profile(rule("state.batteryLevel", "80")));
        invalid(profile(rule("state.batteryLevel", 80.5)));
        invalid(profile(rule("state.batteryLevel", 101)));
        invalid(profile(rule("state.interactive", "false")));
        invalid(profile(rule("state.interactive", JSONObject.NULL)));
        invalid(profile(rule("state.batteryPlugged", 3)));
        invalid(profile(rule("state.batteryLevel", 20), rule("state.batteryLevel", 30)));
        invalid(profile(rule("unknown.key", 0)));
        invalid(profile(rule("time.wallOffsetMillis", 0), rule("time.wallFixedMillis", 0)));
        invalid(profile(rule("sensors.accelerometer", new JSONArray("[1,2]"))));
        invalid(profile(rule("sensors.accelerometer", new JSONArray("[1,2,201]"))));
        invalid(profile(rule("sensors.light", new JSONArray("[-1]"))));
        invalid(profile(rule("locale.timeZone", "Not/AZone")));
        invalid(profile(rule("locale.languageTags", new JSONArray("[\"en-US\",\"EN-us\"]"))));
        invalid(profile(rule("locale.languageTags", new JSONArray("[\"en_US\"]"))));
        invalid(new JSONObject(valid).put("schemaVersion", "1").toString());
        invalid(new JSONObject(valid).put("extra", 1).toString());
        invalid(new JSONObject(valid).put("rules", new JSONArray()).toString());
        invalid(valid + "junk");
        invalid(" ".repeat(DisplayContract.MAX_JSON_CHARS + 1));
        for (String target : List.of("android", "system_server", "dev.display", "dev.posedmcp", "dev.deploy", "*", "com.example:*")) {
            try { DisplayContract.target(target); throw new AssertionError("bad target"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        check(DisplayContract.validateProfile(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))).getJSONArray("rules").length() == 11, "example validates");
        // Test the future port routing, without providing a mock runtime in the shipped APK.
        List<String> called = new ArrayList<>();
        DisplayCore port = (DisplayCore)Proxy.newProxyInstance(DisplayCore.class.getClassLoader(), new Class<?>[]{DisplayCore.class}, (proxy, method, values) -> {
            called.add(method.getName());
            if (method.getName().equals("getTarget")) throw new IllegalStateException("private backend detail");
            return new JSONObject().put("operation", method.getName());
        });
        DisplayEndpoint endpoint = new DisplayEndpoint(port);
        check(new JSONObject(endpoint.reply(() -> endpoint.putProfile(valid, 0, "route"))).getBoolean("ok"), "port routing");
        check(called.equals(List.of("putProfile")), "one call only");
        code(endpoint.reply(() -> endpoint.getTarget("com.example.app")), "CORE_ERROR");
        check(!endpoint.reply(() -> endpoint.getTarget("com.example.app")).contains("private backend detail"), "no backend leak");
        code(endpoint.reply(() -> new JSONObject().put("large", "a".repeat(DisplayContract.MAX_REPLY_CHARS))), "REPLY_TOO_LARGE");
        var pool = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 100; i++) calls.add(() -> new JSONObject(absent.reply(() -> absent.validate(valid))).getBoolean("ok"));
            for (var result : pool.invokeAll(calls)) check(result.get(), "parallel validation");
        } finally { pool.shutdownNow(); }
        System.out.println("FrontendRegression: " + checks + " checks passed");
    }
}
