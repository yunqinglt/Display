package dev.display.core;

import org.json.JSONObject;

/** Future backend port shared by AIDL and Project UI. All results are data objects,
 * not wire envelopes. Implementations must be thread-safe and bounded in time.
 * Mutations are atomic CAS operations; requestId deduplicates identical retries.
 * Store desired revision separately from per-process acknowledged revision.
 * Core must independently verify real LSPosed scope before applying any profile.
 */
public interface DisplayCore {
    JSONObject status() throws Exception;
    JSONObject capabilities() throws Exception;
    JSONObject getProfile(String id) throws Exception;
    JSONObject putProfile(JSONObject profile, long expectedRevision, String requestId) throws Exception;
    JSONObject deleteProfile(String id, long expectedRevision, String requestId) throws Exception;
    JSONObject getTarget(String packageName) throws Exception;
    JSONObject applyProfile(String packageName, String id, long profileRevision,
            long expectedTargetRevision, String requestId) throws Exception;
    JSONObject clearTarget(String packageName, long expectedTargetRevision, String requestId) throws Exception;
    JSONObject getRuntimeState(String packageName) throws Exception;
}
