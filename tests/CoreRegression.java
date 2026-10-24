package dev.display.tests;

import dev.display.api.DisplayContract;
import dev.display.core.CoreException;
import dev.display.core.ProfileCore;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.ArrayList;

/** Real state machine with an in-memory durable-store boundary; no Android mocks. */
public final class CoreRegression {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    interface Attempt { void run() throws Exception; }
    static void code(String code, Attempt attempt) throws Exception {
        try { attempt.run(); throw new AssertionError("Expected " + code); }
        catch (CoreException e) { check(code.equals(e.code), e.code); }
    }
    static final class Store implements ProfileCore.Store {
        String raw; boolean fail;
        public String read() { return raw; }
        public void write(String json) throws Exception { if (fail) throw new java.io.IOException("disk full"); raw = json; }
    }
    static ProfileCore core(Store store) throws Exception {
        return new ProfileCore(store, new ProfileCore.Scope() {
            public boolean contains(String p) { return p.equals("com.example.scoped"); }
            public JSONObject describe() { return new JSONObject(); }
        }, (pkg, revision) -> new JSONObject().put("desiredRevision", revision).put("processes", new JSONArray()));
    }
    static JSONObject profile(int value) throws Exception {
        return DisplayContract.validateProfile("{\"schemaVersion\":1,\"id\":\"test\",\"rules\":[{\"key\":\"state.batteryLevel\",\"value\":" + value + "}]}");
    }
    public static void main(String[] args) throws Exception {
        Store store = new Store(); ProfileCore c = core(store);
        check(c.getTarget("com.example.scoped").getLong("revision") == 0, "empty target");
        JSONObject p = c.putProfile(profile(31), 0, "put");
        check(p.getLong("revision") == 1, "create");
        check(c.putProfile(profile(31), 0, "put").toString().equals(p.toString()), "identical retry");
        code("REQUEST_ID_CONFLICT", () -> c.putProfile(profile(32), 0, "put"));
        code("REVISION_CONFLICT", () -> c.putProfile(profile(31), 0, "other"));
        code("NOT_IN_SCOPE", () -> c.applyProfile("com.example.outside", "test", 1, 0, "out"));
        code("REVISION_CONFLICT", () -> c.applyProfile("com.example.scoped", "test", 2, 0, "bad-version"));
        check(!c.applyProfile("com.example.scoped", "test", 1, 0, "apply").getBoolean("applied"), "desired != applied");
        code("PROFILE_IN_USE", () -> c.deleteProfile("test", 1, "delete-in-use"));
        c.putProfile(profile(32), 1, "update");
        check(c.snapshot("com.example.scoped").getJSONArray("rules").getJSONObject(0).getInt("value") == 31, "target pins immutable old revision");
        ProfileCore restarted = core(store);
        check(restarted.getProfile("test").getLong("revision") == 2, "persisted restart");
        check(restarted.putProfile(profile(31), 0, "put").getLong("revision") == 1, "receipt persisted with state");
        code("REVISION_CONFLICT", () -> restarted.clearTarget("com.example.scoped", 0, "stale-clear"));
        restarted.clearTarget("com.example.scoped", 1, "clear");
        check(restarted.snapshot("com.example.scoped").getJSONArray("rules").length() == 0, "clear restores originals");
        check(restarted.getTarget("com.example.scoped").getLong("revision") == 2, "target tombstone");
        restarted.deleteProfile("test", 2, "delete");
        code("NOT_FOUND", () -> restarted.getProfile("test"));
        code("REVISION_CONFLICT", () -> restarted.putProfile(profile(34), 0, "aba"));
        restarted.putProfile(profile(34), 3, "recreate");
        check(restarted.getProfile("test").getLong("revision") == 4, "profile tombstone prevents ABA");
        store.fail = true;
        try { restarted.putProfile(profile(35), 4, "disk-fail"); throw new AssertionError("failed disk write succeeded"); }
        catch (java.io.IOException expected) { check(restarted.getProfile("test").getLong("revision") == 4, "failed write not published"); }
        store.fail = false;
        check(restarted.putProfile(profile(35), 4, "disk-fail").getLong("revision") == 5, "retry after failed disk write");
        var executor = Executors.newFixedThreadPool(8);
        try {
            var jobs = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 32; i++) { final String id = "concurrent-" + i;
                jobs.add(() -> { try { restarted.putProfile(profile(40), 5, id); return true; }
                    catch (CoreException e) { if (!e.code.equals("REVISION_CONFLICT")) throw e; return false; } }); }
            int wins = 0; for (var result : executor.invokeAll(jobs)) if (result.get()) wins++;
            check(wins == 1, "one concurrent CAS winner");
        } finally { executor.shutdownNow(); }
        String valid = "{\"schemaVersion\":1,\"id\":\"gps\",\"rules\":[{\"key\":\"location.position\",\"value\":{\"latitude\":35.681236,\"longitude\":139.767125,\"accuracyMeters\":12}}]}";
        DisplayContract.validateProfile(valid); checks++;
        for (String bad : new String[]{valid.replace("35.681236", "91"), valid.replace("139.767125", "-181"), valid.replace("12}", "0}"), valid.replace("12}", "12,\"extra\":1}")}) {
            try { DisplayContract.validateProfile(bad); throw new AssertionError("invalid location accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("Core regression passed: " + checks + " checks");
    }
}
