package dev.display;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import dev.display.api.DisplayContract;
import dev.display.api.IDisplay;

/** Signature-protected control endpoint. Runtime peers use a separate read-only channel. */
public final class DisplayAidlService extends Service {
    private DisplayEndpoint endpoint;
    @Override public void onCreate() {
        super.onCreate();
        endpoint = new DisplayEndpoint(((DisplayApplication)getApplication()).core());
    }
    private String call(DisplayEndpoint.Call call) {
        try { CallerPolicy.enforce(this); }
        catch (SecurityException e) { return DisplayContract.error("UNAUTHORIZED", e.getMessage()).toString(); }
        return endpoint.reply(call);
    }
    private final IDisplay.Stub binder = new IDisplay.Stub() {
        public String ping() { return call(endpoint::ping); }
        public String status() { return call(endpoint::status); }
        public String capabilities() { return call(endpoint::capabilities); }
        public String validateProfile(String json) { return call(() -> endpoint.validate(json)); }
        public String getProfile(String id) { return call(() -> endpoint.getProfile(id)); }
        public String putProfile(String json, long revision, String request) {
            return call(() -> endpoint.putProfile(json, revision, request));
        }
        public String deleteProfile(String id, long revision, String request) {
            return call(() -> endpoint.deleteProfile(id, revision, request));
        }
        public String getTarget(String pkg) { return call(() -> endpoint.getTarget(pkg)); }
        public String applyProfile(String pkg, String id, long profileRevision, long targetRevision, String request) {
            return call(() -> endpoint.apply(pkg, id, profileRevision, targetRevision, request));
        }
        public String clearTarget(String pkg, long revision, String request) {
            return call(() -> endpoint.clear(pkg, revision, request));
        }
        public String getRuntimeState(String pkg) { return call(() -> endpoint.runtime(pkg)); }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
