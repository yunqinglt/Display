package dev.display.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** In-process Project UI port. No additional exported Binder surface. */
public interface ProjectCore {
    /** Reads a pinned active binding, otherwise the app's saved disabled draft. */
    JSONObject getProject(String packageName) throws Exception;
    /** Atomically persists draft and enablement; conflicts on either profile or target revision. */
    JSONObject saveProject(String packageName, JSONArray rules, long expectedProfileRevision,
            long expectedTargetRevision, boolean enabled, String requestId) throws Exception;
}
