package dev.display.hooks;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import dev.display.api.DisplayContract;
import dev.display.api.IDisplayRuntime;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Binder and polling stay off all framework hot paths. Stale configurations fail open. */
final class RuntimeClient {
    private final Application app;
    private final String pkg;
    private final HookEngine engine;
    private final String session = UUID.randomUUID().toString();
    private volatile IDisplayRuntime remote;
    private volatile CountDownLatch connected;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IDisplayRuntime.Stub.asInterface(binder); CountDownLatch latch = connected; if (latch != null) latch.countDown();
        }
        public void onServiceDisconnected(ComponentName name) { remote = null; }
        public void onBindingDied(ComponentName name) { remote = null; }
        public void onNullBinding(ComponentName name) { remote = null; CountDownLatch latch = connected; if (latch != null) latch.countDown(); }
    };
    RuntimeClient(Application app, String pkg, HookEngine engine) { this.app = app; this.pkg = pkg; this.engine = engine; }
    void start() { Thread worker = new Thread(this::run, "Display-runtime"); worker.setDaemon(true); worker.start(); }
    private void disconnect() {
        if (bound) { try { app.unbindService(connection); } catch (RuntimeException ignored) {} }
        bound = false; remote = null;
    }
    private void run() {
        long lastSuccess = android.os.SystemClock.elapsedRealtime();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (remote == null) {
                    disconnect(); connected = new CountDownLatch(1);
                    bound = true;
                    boolean accepted = app.bindService(new Intent().setComponent(new ComponentName("dev.display", "dev.display.DisplayRuntimeService")),
                            Context.BIND_AUTO_CREATE, app.getMainExecutor(), connection);
                    if (!accepted || !connected.await(4, TimeUnit.SECONDS) || remote == null) throw new IllegalStateException("runtime bind unavailable");
                }
                String raw = remote.exchange(pkg, Application.getProcessName(), session, engine.report().toString());
                JSONObject reply = DisplayContract.decodeReply(raw);
                if (!reply.getBoolean("ok")) throw new IllegalStateException("runtime rejected");
                engine.update(reply.getJSONObject("data"));
                lastSuccess = android.os.SystemClock.elapsedRealtime();
                Thread.sleep(1000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) {
                disconnect();
                if (android.os.SystemClock.elapsedRealtime() - lastSuccess > 10_000) engine.reset();
                try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
        disconnect(); engine.reset();
    }
}
