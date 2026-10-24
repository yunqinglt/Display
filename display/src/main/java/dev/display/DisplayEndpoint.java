package dev.display;

import dev.display.api.DisplayContract;
import dev.display.core.DisplayCore;
import dev.display.core.CoreException;
import org.json.JSONException;
import org.json.JSONObject;

/** Transport-independent frontend. A null port means Core has not been implemented. */
public final class DisplayEndpoint {
    private final DisplayCore core;
    public DisplayEndpoint(DisplayCore core) { this.core = core; }
    public interface Call { JSONObject run() throws Exception; }
    public String reply(Call call) {
        try {
            String reply = DisplayContract.ok(call.run()).toString();
            if (reply.length() > DisplayContract.MAX_REPLY_CHARS)
                return DisplayContract.error("REPLY_TOO_LARGE", "Core reply exceeds control-plane limit").toString();
            return reply;
        } catch (IllegalArgumentException | JSONException e) {
            return DisplayContract.error("INVALID_ARGUMENT", e.getMessage()).toString();
        } catch (CoreException e) {
            return DisplayContract.error(e.code, e.getMessage()).toString();
        } catch (CoreUnavailable e) {
            return DisplayContract.error("CORE_UNAVAILABLE", "DisplayCore is not implemented; no configuration was saved or applied").toString();
        } catch (Exception e) {
            return DisplayContract.error("CORE_ERROR", "DisplayCore operation failed; query state before retrying a mutation").toString();
        }
    }
    private DisplayCore core() throws CoreUnavailable {
        if (core == null) throw new CoreUnavailable();
        return core;
    }
    private static final class CoreUnavailable extends Exception {}
    public JSONObject ping() throws JSONException {
        return new JSONObject().put("name", "Display").put("version", "0.4.0");
    }
    public JSONObject status() throws Exception {
        return new JSONObject().put("name", "Display").put("version", "0.4.0")
                .put("phase", "project-ui").put("coreAvailable", core != null)
                .put("frameworkStatus", core == null ? JSONObject.NULL : core.status())
                .put("projectUiAvailable", true);
    }
    public JSONObject capabilities() throws Exception {
        return core == null ? DisplayContract.capabilities() : core.capabilities();
    }
    public JSONObject validate(String json) throws Exception {
        JSONObject profile = DisplayContract.validateProfile(json);
        return new JSONObject().put("valid", true).put("profile", profile)
                .put("runtimeVerified", false).put("persisted", false).put("applied", false);
    }
    public JSONObject getProfile(String id) throws Exception { DisplayContract.id(id); return core().getProfile(id); }
    public JSONObject putProfile(String json, long revision, String request) throws Exception {
        JSONObject profile = DisplayContract.validateProfile(json);
        DisplayContract.revision(revision); DisplayContract.id(request);
        return core().putProfile(profile, revision, request);
    }
    public JSONObject deleteProfile(String id, long revision, String request) throws Exception {
        DisplayContract.id(id); DisplayContract.revision(revision); DisplayContract.id(request);
        return core().deleteProfile(id, revision, request);
    }
    public JSONObject getTarget(String pkg) throws Exception {
        DisplayContract.target(pkg); return core().getTarget(pkg);
    }
    public JSONObject apply(String pkg, String id, long profileRevision, long targetRevision, String request) throws Exception {
        DisplayContract.target(pkg); DisplayContract.id(id); DisplayContract.id(request);
        DisplayContract.revision(profileRevision); DisplayContract.revision(targetRevision);
        if (profileRevision == 0) throw new IllegalArgumentException("apply requires a persisted profile revision > 0");
        return core().applyProfile(pkg, id, profileRevision, targetRevision, request);
    }
    public JSONObject clear(String pkg, long revision, String request) throws Exception {
        DisplayContract.target(pkg); DisplayContract.revision(revision); DisplayContract.id(request);
        return core().clearTarget(pkg, revision, request);
    }
    public JSONObject runtime(String pkg) throws Exception {
        DisplayContract.target(pkg); return core().getRuntimeState(pkg);
    }
}
