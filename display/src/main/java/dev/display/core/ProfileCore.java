package dev.display.core;

import dev.display.api.DisplayContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Copy-on-write state, one durable commit for data, tombstones and retry receipts. */
public final class ProfileCore implements DisplayCore, ProjectCore {
    public interface Store { String read() throws Exception; void write(String json) throws Exception; }
    public interface Scope { boolean contains(String pkg) throws Exception; JSONObject describe() throws Exception; }
    public interface Runtime { JSONObject state(String pkg, long desired) throws Exception; }
    private static final int RECEIPTS = 256, MAX_ENTRIES = 128, MAX_STATE = 2_097_152;
    private final Store store;
    private final Scope scope;
    private final Runtime runtime;
    private JSONObject state;
    public ProfileCore(Store store, Scope scope, Runtime runtime) throws Exception {
        this.store = store; this.scope = scope; this.runtime = runtime;
        String raw = store.read();
        state = raw == null ? new JSONObject().put("format", 1).put("profiles", new JSONObject())
                .put("targets", new JSONObject()).put("receipts", new JSONArray()) : new JSONObject(raw);
        if (state.getInt("format") != 1 || state.optJSONObject("profiles") == null
                || state.optJSONObject("targets") == null || state.optJSONArray("receipts") == null)
            throw new IllegalStateException("Unsupported/corrupt Display state; refusing to overwrite it");
    }
    public synchronized JSONObject status() throws Exception {
        return new JSONObject().put("storage", "atomic-private-file").put("retryReceiptLimit", RECEIPTS)
                .put("entryLimit", MAX_ENTRIES).put("framework", scope.describe())
                .put("runtimeChannel", "scoped-uid-binder").put("scopeSource", "framework-service");
    }
    public JSONObject capabilities() throws Exception {
        JSONObject result = DisplayContract.capabilities();
        result.put("coreAvailable", true).put("validationOnly", false).put("runtimeSupport", "query-per-process")
                .put("minimumFrameworkApi", 102);
        JSONArray rules = result.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) rules.getJSONObject(i).put("runtimeSupported", JSONObject.NULL)
                .put("backendImplemented", true);
        return result;
    }
    private static CoreException failure(String code, String message) { return new CoreException(code, message); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static long rev(JSONObject row) { return row == null ? 0 : row.optLong("revision", 0); }
    private static void cas(long expected, JSONObject row) throws CoreException {
        if (rev(row) != expected) throw failure("REVISION_CONFLICT", "Expected revision does not match current revision " + rev(row));
        if (expected == 9_007_199_254_740_991L) throw failure("REVISION_EXHAUSTED", "Revision limit reached");
    }
    private static JSONObject profileResult(JSONObject row) throws Exception {
        if (row == null || row.optBoolean("deleted")) throw failure("NOT_FOUND", "Profile does not exist");
        return new JSONObject().put("profile", copy(row.getJSONObject("profile"))).put("revision", rev(row));
    }
    public synchronized JSONObject getProfile(String id) throws Exception {
        return profileResult(state.getJSONObject("profiles").optJSONObject(id));
    }
    // Sort object keys recursively: JSON member order is not an idempotency parameter.
    private static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject)value; List<String> keys = new ArrayList<>();
            o.keys().forEachRemaining(keys::add); Collections.sort(keys);
            StringBuilder s = new StringBuilder("{");
            for (String key : keys) s.append(JSONObject.quote(key)).append(':').append(canonical(o.get(key))).append(',');
            return s.append('}').toString();
        }
        if (value instanceof JSONArray) {
            StringBuilder s = new StringBuilder("["); JSONArray a = (JSONArray)value;
            for (int i = 0; i < a.length(); i++) s.append(canonical(a.get(i))).append(',');
            return s.append(']').toString();
        }
        return new JSONArray().put(value).toString();
    }
    private static String fingerprint(String op, JSONArray args) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest((op + canonical(args)).getBytes(StandardCharsets.UTF_8));
        StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    private JSONObject prior(String id, String hash) throws Exception {
        JSONArray receipts = state.getJSONArray("receipts");
        for (int i = 0; i < receipts.length(); i++) {
            JSONObject receipt = receipts.getJSONObject(i);
            if (!receipt.getString("id").equals(id)) continue;
            if (!receipt.getString("hash").equals(hash)) throw failure("REQUEST_ID_CONFLICT", "requestId was already committed with different parameters");
            return copy(receipt.getJSONObject("result"));
        }
        return null;
    }
    private JSONObject commit(JSONObject next, String id, String hash, JSONObject result) throws Exception {
        JSONArray receipts = next.getJSONArray("receipts");
        receipts.put(new JSONObject().put("id", id).put("hash", hash).put("result", result));
        if (receipts.length() > RECEIPTS) receipts.remove(0);
        String raw = next.toString();
        if (raw.length() > MAX_STATE) throw failure("STORAGE_LIMIT", "State quota exceeded; clear old targets/profiles");
        store.write(raw); // No in-memory publication on failed durable commit.
        state = next;
        return copy(result);
    }
    private static void quota(JSONObject table, String key) throws CoreException {
        if (!table.has(key) && table.length() >= MAX_ENTRIES) throw failure("ENTRY_LIMIT", "Entry/tombstone quota reached");
    }
    public synchronized JSONObject putProfile(JSONObject profile, long expected, String request) throws Exception {
        profile = DisplayContract.validateProfile(profile.toString());
        String id = profile.getString("id"), hash = fingerprint("put", new JSONArray().put(profile).put(expected));
        JSONObject old = prior(request, hash); if (old != null) return old;
        JSONObject next = copy(state), table = next.getJSONObject("profiles"); quota(table, id);
        cas(expected, table.optJSONObject(id));
        JSONObject row = new JSONObject().put("profile", profile).put("revision", expected + 1);
        table.put(id, row);
        return commit(next, request, hash, profileResult(row).put("persisted", true));
    }
    public synchronized JSONObject deleteProfile(String id, long expected, String request) throws Exception {
        String hash = fingerprint("delete", new JSONArray().put(id).put(expected));
        JSONObject old = prior(request, hash); if (old != null) return old;
        JSONObject next = copy(state), table = next.getJSONObject("profiles");
        JSONObject row = table.optJSONObject(id); cas(expected, row); profileResult(row);
        JSONObject targets = next.getJSONObject("targets"); Iterator<String> keys = targets.keys();
        while (keys.hasNext()) if (id.equals(targets.getJSONObject(keys.next()).optString("profileId")))
            throw failure("PROFILE_IN_USE", "Clear all referencing targets before deleting a profile");
        table.put(id, new JSONObject().put("revision", expected + 1).put("deleted", true));
        return commit(next, request, hash, new JSONObject().put("profileId", id).put("revision", expected + 1).put("deleted", true));
    }
    private JSONObject target(JSONObject root, String pkg) throws Exception {
        JSONObject row = root.getJSONObject("targets").optJSONObject(pkg);
        if (row == null) return new JSONObject().put("package", pkg).put("profileId", JSONObject.NULL).put("profileRevision", 0).put("revision", 0);
        JSONObject result = copy(row); result.remove("profile"); return result;
    }
    public synchronized JSONObject getTarget(String pkg) throws Exception { return target(state, pkg); }
    public synchronized JSONObject applyProfile(String pkg, String id, long profileRevision, long expected, String request) throws Exception {
        String hash = fingerprint("apply", new JSONArray().put(pkg).put(id).put(profileRevision).put(expected));
        JSONObject old = prior(request, hash); if (old != null) return old;
        if (!scope.contains(pkg)) throw failure("NOT_IN_SCOPE", "Display is not enabled for this package in the framework");
        JSONObject next = copy(state), table = next.getJSONObject("targets"); quota(table, pkg); cas(expected, table.optJSONObject(pkg));
        JSONObject profile = profileResult(next.getJSONObject("profiles").optJSONObject(id));
        if (profile.getLong("revision") != profileRevision) throw failure("REVISION_CONFLICT", "Profile revision has changed");
        table.put(pkg, new JSONObject().put("package", pkg).put("profileId", id).put("profileRevision", profileRevision)
                .put("revision", expected + 1).put("profile", profile.getJSONObject("profile")));
        return commit(next, request, hash, target(next, pkg).put("desiredUpdated", true).put("applied", false));
    }
    public synchronized JSONObject clearTarget(String pkg, long expected, String request) throws Exception {
        String hash = fingerprint("clear", new JSONArray().put(pkg).put(expected));
        JSONObject old = prior(request, hash); if (old != null) return old;
        JSONObject next = copy(state), table = next.getJSONObject("targets"); quota(table, pkg); cas(expected, table.optJSONObject(pkg));
        table.put(pkg, new JSONObject().put("package", pkg).put("profileId", JSONObject.NULL).put("profileRevision", 0).put("revision", expected + 1));
        return commit(next, request, hash, target(next, pkg).put("desiredUpdated", true).put("applied", false));
    }
    public synchronized JSONObject getRuntimeState(String pkg) throws Exception {
        JSONObject result = runtime.state(pkg, getTarget(pkg).getLong("revision"));
        return result.put("scopeVerified", scope.contains(pkg)).put("scopeSource", "framework-service");
    }
    private static String projectId(String pkg) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(pkg.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder("project.");
        for (int i = 0; i < 16; i++) id.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
        return id.toString();
    }
    private JSONObject project(JSONObject root, String pkg) throws Exception {
        String id = projectId(pkg);
        JSONObject draft = root.getJSONObject("profiles").optJSONObject(id);
        JSONObject binding = root.getJSONObject("targets").optJSONObject(pkg);
        boolean enabled = binding != null && binding.has("profile");
        JSONObject source = enabled ? binding.getJSONObject("profile")
                : draft != null && !draft.optBoolean("deleted") ? draft.getJSONObject("profile") : null;
        return new JSONObject().put("package", pkg).put("profileRevision", rev(draft))
                .put("targetRevision", rev(binding)).put("enabled", enabled)
                .put("rules", source == null ? new JSONArray() : new JSONArray(source.getJSONArray("rules").toString()));
    }
    /** Local UI port; active targets always read their pinned snapshot, including AIDL bindings. */
    public synchronized JSONObject getProject(String pkg) throws Exception {
        DisplayContract.target(pkg);
        return project(state, pkg);
    }
    /** Draft + target are committed together, with CAS against both independently mutable rows. */
    public synchronized JSONObject saveProject(String pkg, JSONArray rules, long profileRevision,
            long targetRevision, boolean enabled, String request) throws Exception {
        DisplayContract.target(pkg); DisplayContract.id(request);
        DisplayContract.revision(profileRevision); DisplayContract.revision(targetRevision);
        String id = projectId(pkg);
        JSONObject profile = new JSONObject().put("schemaVersion", 1).put("id", id).put("rules", rules);
        if (rules.length() > 0) profile = DisplayContract.validateProfile(profile.toString());
        else if (enabled) throw new IllegalArgumentException("请先设置至少一个接口值");
        String hash = fingerprint("project", new JSONArray().put(pkg).put(rules).put(profileRevision).put(targetRevision).put(enabled));
        JSONObject old = prior(request, hash); if (old != null) return old;
        JSONObject next = copy(state), profiles = next.getJSONObject("profiles"), targets = next.getJSONObject("targets");
        cas(profileRevision, profiles.optJSONObject(id)); cas(targetRevision, targets.optJSONObject(pkg));
        if (enabled && !scope.contains(pkg)) throw failure("NOT_IN_SCOPE", "应用已不在 Display 的作用域中");
        quota(profiles, id);
        if (rules.length() == 0) {
            Iterator<String> keys = targets.keys();
            while (keys.hasNext()) {
                String other = keys.next();
                if (!other.equals(pkg) && id.equals(targets.getJSONObject(other).optString("profileId")))
                    throw failure("PROFILE_IN_USE", "此配置仍被其他应用引用，暂不能清空");
            }
            profiles.put(id, new JSONObject().put("revision", profileRevision + 1).put("deleted", true));
        } else profiles.put(id, new JSONObject().put("revision", profileRevision + 1).put("profile", profile));
        if (enabled) {
            quota(targets, pkg);
            targets.put(pkg, new JSONObject().put("package", pkg).put("profileId", id)
                    .put("profileRevision", profileRevision + 1).put("revision", targetRevision + 1).put("profile", profile));
        } else {
            JSONObject previous = targets.optJSONObject(pkg);
            if (previous != null && previous.has("profile")) targets.put(pkg, new JSONObject().put("package", pkg)
                    .put("profileId", JSONObject.NULL).put("profileRevision", 0).put("revision", targetRevision + 1));
        }
        return commit(next, request, hash, project(next, pkg).put("persisted", true).put("applied", false));
    }
    /** Snapshot for a runtime caller whose UID and framework scope were already checked. */
    public synchronized JSONObject snapshot(String pkg) throws Exception {
        JSONObject result = target(state, pkg);
        JSONObject row = state.getJSONObject("targets").optJSONObject(pkg);
        return result.put("rules", row != null && row.has("profile")
                ? new JSONArray(row.getJSONObject("profile").getJSONArray("rules").toString()) : new JSONArray());
    }
}
