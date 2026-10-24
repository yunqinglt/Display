package dev.display.tests;

import dev.display.api.DisplayContract;
import dev.display.core.CoreException;
import dev.display.core.ProfileCore;
import dev.display.ui.InterfaceCatalog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** Primitive forms and atomic UI/AIDL state transitions, without Android widget mocks. */
public final class ProjectRegression {
    private static int checks;
    private static final String APP = "com.example.scoped", OTHER = "com.example.other";
    private interface Attempt { void run() throws Exception; }
    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private static void rejects(Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid input accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void code(String expected, Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (CoreException e) { check(e.code.equals(expected), "wrong error: " + e.code); }
    }
    private static final class Store implements ProfileCore.Store {
        String raw; int writes; boolean fail;
        public String read() { return raw; }
        public void write(String value) throws Exception { if (fail) throw new java.io.IOException("full"); raw = value; writes++; }
    }
    private static ProfileCore core(Store store) throws Exception {
        return new ProfileCore(store, new ProfileCore.Scope() {
            public boolean contains(String pkg) { return Set.of(APP, OTHER).contains(pkg); }
            public JSONObject describe() { return new JSONObject(); }
        }, (pkg, revision) -> new JSONObject().put("desiredRevision", revision));
    }
    private static JSONArray battery(int percent) throws Exception {
        return new JSONArray().put(new JSONObject().put("key", "state.batteryLevel").put("value", percent));
    }
    private static JSONObject save(ProfileCore core, String pkg, JSONObject previous, JSONArray rules, boolean enabled, String request) throws Exception {
        return core.saveProject(pkg, rules, previous.getLong("profileRevision"), previous.getLong("targetRevision"), enabled, request);
    }
    public static void main(String[] ignored) throws Exception {
        Set<String> keys = new HashSet<>();
        for (InterfaceCatalog.Spec spec : InterfaceCatalog.SPECS) {
            check(keys.add(spec.key()), "unique UI rule"); List<String> input;
            if (spec.languages()) input = List.of("zh-CN", "en-US");
            else if (spec.key().equals("locale.timeZone")) input = List.of("Asia/Shanghai");
            else if (spec.key().equals("location.position")) input = List.of("35.681236", "139.767125", "12");
            else if (spec.key().equals("network.wifiRssi")) input = List.of("-55");
            else if (spec.fields().get(0).kind() == InterfaceCatalog.Kind.BOOLEAN) input = List.of("false");
            else if (spec.fields().get(0).kind() == InterfaceCatalog.Kind.CHOICE) input = List.of(spec.choices()[0]);
            else { List<String> samples = new ArrayList<>(); for (var field : spec.fields()) samples.add("1"); input = samples; }
            Object value = InterfaceCatalog.parse(spec, input);
            check(InterfaceCatalog.values(spec, value).size() == input.size(), "primitive round trip: " + spec.key());
        }
        JSONArray catalogue = DisplayContract.capabilities().getJSONArray("rules");
        check(keys.size() == catalogue.length(), "complete catalogue");
        for (int i = 0; i < catalogue.length(); i++) check(keys.contains(catalogue.getJSONObject(i).getString("key")), "contract rule covered");
        var location = InterfaceCatalog.find("location.position");
        rejects(() -> InterfaceCatalog.parse(location, List.of("1")));
        rejects(() -> InterfaceCatalog.parse(location, List.of("91", "0", "12")));
        rejects(() -> InterfaceCatalog.parse(location, List.of("NaN", "0", "12")));
        rejects(() -> InterfaceCatalog.parse(location, List.of("{\"latitude\":1}", "0", "12")));
        rejects(() -> InterfaceCatalog.parse(InterfaceCatalog.find("state.batteryLevel"), List.of("1.2")));
        rejects(() -> InterfaceCatalog.parse(InterfaceCatalog.find("state.batteryLevel"), List.of("101")));
        rejects(() -> InterfaceCatalog.parse(InterfaceCatalog.find("state.powerSave"), List.of("0")));
        rejects(() -> InterfaceCatalog.parse(InterfaceCatalog.find("network.transport"), List.of("unknown")));
        rejects(() -> InterfaceCatalog.parse(InterfaceCatalog.find("locale.languageTags"), List.of("en-US", "EN-us")));
        check(((JSONArray)InterfaceCatalog.parse(InterfaceCatalog.find("locale.languageTags"), List.of("", "zh-CN"))).length() == 1, "blank languages omitted");
        JSONArray pair = new JSONArray().put(new JSONObject().put("key", "time.wallOffsetMillis").put("value", 100)).put(new JSONObject().put("key", "state.powerSave").put("value", false));
        JSONArray replaced = InterfaceCatalog.replace(pair, "time.wallFixedMillis", 1000L);
        check(InterfaceCatalog.value(replaced, "time.wallOffsetMillis") == null, "exclusive clock mode removed");
        check(Boolean.FALSE.equals(InterfaceCatalog.value(replaced, "state.powerSave")), "false rule retained");
        check(pair.length() == 2 && InterfaceCatalog.value(pair, "time.wallOffsetMillis") != null, "form edit does not mutate input");

        Store store = new Store(); ProfileCore c = core(store); JSONObject initial = c.getProject(APP);
        check(!initial.getBoolean("enabled") && initial.getJSONArray("rules").length() == 0, "initial state");
        rejects(() -> save(c, APP, initial, new JSONArray(), true, "empty-enable"));
        JSONObject draft = save(c, APP, initial, battery(37), false, "draft");
        check(store.writes == 1 && draft.getLong("profileRevision") == 1 && draft.getLong("targetRevision") == 0, "disabled draft is one commit without target creation");
        JSONObject enabled = save(c, APP, draft, battery(37), true, "enable");
        check(store.writes == 2 && enabled.getBoolean("enabled") && enabled.getLong("targetRevision") == 1, "enable atomic commit");
        check(c.snapshot(APP).getJSONArray("rules").getJSONObject(0).getInt("value") == 37, "runtime receives UI draft");
        check(save(c, APP, draft, battery(37), true, "enable").toString().equals(enabled.toString()) && store.writes == 2, "UI retries are idempotent");
        code("REQUEST_ID_CONFLICT", () -> save(c, APP, draft, battery(38), true, "enable"));
        code("REVISION_CONFLICT", () -> save(c, APP, draft, battery(38), true, "stale"));
        String beforeFailure = store.raw; int writesBeforeFailure = store.writes; store.fail = true;
        try { save(c, APP, enabled, battery(38), true, "disk"); throw new AssertionError("disk failure ignored"); }
        catch (java.io.IOException expected) { check(store.raw.equals(beforeFailure) && store.writes == writesBeforeFailure, "disk remains unchanged"); }
        check(c.getProject(APP).getLong("profileRevision") == enabled.getLong("profileRevision")
                && c.snapshot(APP).getJSONArray("rules").getJSONObject(0).getInt("value") == 37, "neither draft nor target published on failure");
        store.fail = false;
        JSONObject updated = save(c, APP, enabled, battery(38), true, "disk");
        JSONObject disabled = save(c, APP, updated, battery(38), false, "off");
        check(!disabled.getBoolean("enabled") && disabled.getJSONArray("rules").length() == 1 && c.snapshot(APP).getJSONArray("rules").length() == 0, "off preserves draft and clears runtime");
        ProfileCore restarted = core(store);
        check(restarted.getProject(APP).getJSONArray("rules").getJSONObject(0).getInt("value") == 38, "disabled draft persists restart");
        JSONObject cleared = save(c, APP, disabled, new JSONArray(), false, "remove-last");
        check(cleared.getJSONArray("rules").length() == 0 && !cleared.getBoolean("enabled"), "empty project tombstone");
        JSONObject recreated = save(c, APP, cleared, battery(39), true, "recreate");
        check(recreated.getLong("profileRevision") > cleared.getLong("profileRevision"), "empty/recreate preserves version");
        JSONArray leaked = c.getProject(APP).getJSONArray("rules"); leaked.getJSONObject(0).put("value", 90);
        check(c.snapshot(APP).getJSONArray("rules").getJSONObject(0).getInt("value") == 39, "returned project values are detached");
        c.clearTarget(APP, recreated.getLong("targetRevision"), "aidl-clear");
        code("REVISION_CONFLICT", () -> save(c, APP, recreated, battery(40), true, "ui-after-aidl-clear"));
        check(c.getProject(APP).getJSONArray("rules").getJSONObject(0).getInt("value") == 39, "AIDL conflict does not publish UI edit");
        JSONObject current = c.getProject(APP);
        String ownProfile = "project.";
        // Activate and use AIDL's returned ID to modify the same stored row concurrently.
        JSONObject active = save(c, APP, current, battery(40), true, "active-again");
        ownProfile = c.getTarget(APP).getString("profileId");
        JSONObject aidlProfile = c.getProfile(ownProfile).getJSONObject("profile"); aidlProfile.getJSONArray("rules").getJSONObject(0).put("value", 41);
        c.putProfile(aidlProfile, active.getLong("profileRevision"), "aidl-profile-edit");
        check(c.getProject(APP).getJSONArray("rules").getJSONObject(0).getInt("value") == 40, "active UI reads pinned target, not changed latest profile");
        code("REVISION_CONFLICT", () -> save(c, APP, active, battery(42), true, "ui-after-aidl-profile"));
        JSONObject fresh = c.getProject(APP);
        c.applyProfile(OTHER, ownProfile, fresh.getLong("profileRevision"), 0, "shared-profile");
        code("PROFILE_IN_USE", () -> save(c, APP, fresh, new JSONArray(), false, "remove-shared"));
        check(c.getProject(APP).getBoolean("enabled"), "failed clear is atomic");
        JSONObject outside = c.getProject("com.example.outside");
        code("NOT_IN_SCOPE", () -> save(c, "com.example.outside", outside, battery(33), true, "outside"));
        check(c.getProject("com.example.outside").getLong("profileRevision") == 0, "scope rejection does not save draft");
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>(); JSONObject race = c.getProject(APP);
            for (int i = 0; i < 20; i++) { final String request = "race-" + i;
                jobs.add(() -> { try { save(c, APP, race, battery(44), true, request); return true; }
                    catch (CoreException e) { if (!e.code.equals("REVISION_CONFLICT")) throw e; return false; } }); }
            int wins = 0; for (var result : executor.invokeAll(jobs)) if (result.get()) wins++;
            check(wins == 1, "one UI/AIDL CAS winner");
        } finally { executor.shutdownNow(); }
        System.out.println("Project regression passed: " + checks + " checks");
    }
}
