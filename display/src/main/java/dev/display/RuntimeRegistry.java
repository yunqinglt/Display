package dev.display;

import android.os.SystemClock;
import dev.display.core.ProfileCore;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Reports are cooperative process diagnostics, not remote attestation. No ambient device data. */
public final class RuntimeRegistry implements ProfileCore.Runtime {
    private record Peer(String pkg, int pid, long seen, JSONObject report) {}
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    public synchronized void report(String pkg, int pid, String process, String session, JSONObject report) throws Exception {
        if (report.toString().length() > 16_384 || process.length() > 255 || !process.matches("[A-Za-z0-9_.:]+"))
            throw new IllegalArgumentException("Invalid runtime report");
        long ack = dev.display.api.DisplayContract.integer(report.get("ackRevision"), 0, 9_007_199_254_740_991L);
        JSONArray support = report.getJSONArray("supportedRules"), unsupported = report.getJSONArray("unsupportedRules");
        if (support.length() > 64 || unsupported.length() > 64) throw new IllegalArgumentException("Too many runtime rules");
        JSONObject clean = new JSONObject().put("processName", process).put("pid", pid).put("sessionId", session)
                .put("ackRevision", ack).put("supportedRules", support).put("unsupportedRules", unsupported)
                .put("observations", report.optJSONObject("observations")).put("restartRequired", false)
                .put("hookFailures", report.optJSONArray("hookFailures"))
                .put("reportTrust", "scoped-uid-cooperative");
        // A restarted PID/session replaces its previous report; stale sessions expire.
        peers.entrySet().removeIf(e -> SystemClock.elapsedRealtime() - e.getValue().seen > 60_000
                || (e.getValue().pid == pid && e.getValue().pkg.equals(pkg)));
        if (peers.size() >= 64) peers.remove(peers.keySet().iterator().next());
        peers.put(pkg + ":" + session, new Peer(pkg, pid, SystemClock.elapsedRealtime(), clean));
    }
    public synchronized JSONObject state(String pkg, long desired) throws Exception {
        JSONArray processes = new JSONArray(); long now = SystemClock.elapsedRealtime();
        for (Peer peer : peers.values()) {
            if (!peer.pkg.equals(pkg) || now - peer.seen > 60_000) continue;
            JSONObject row = new JSONObject(peer.report.toString());
            long age = now - peer.seen;
            row.put("ageMillis", age).put("state", age > 10_000 ? "stale"
                    : row.getLong("ackRevision") != desired ? "pending"
                    : row.getJSONArray("unsupportedRules").length() > 0 ? "partial" : "active");
            processes.put(row);
        }
        return new JSONObject().put("package", pkg).put("desiredRevision", desired).put("processes", processes);
    }
}
