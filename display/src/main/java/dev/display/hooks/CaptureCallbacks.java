package dev.display.hooks;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Callback identity/lifetime and executor policy, independent of Android for regression tests. */
public final class CaptureCallbacks {
    public static final String SCREENSHOT = "capture.screenshotEvent", RECORDING = "capture.recordingVisible";
    private final Function<String, Object> rules;
    private final Consumer<Throwable> errors;
    private final List<Entry> entries = new ArrayList<>();
    public CaptureCallbacks(Function<String, Object> rules, Consumer<Throwable> errors) {
        this.rules = rules; this.errors = errors;
    }
    public final class Entry {
        private final WeakReference<Object> owner, callback;
        private final boolean recording;
        private WeakReference<Object> wrapper = new WeakReference<>(null);
        private WeakReference<Executor> executor = new WeakReference<>(null);
        private WeakReference<Runnable> screenshotDelivery = new WeakReference<>(null);
        private volatile boolean active;
        private volatile int real;
        private boolean hasRealEvent;
        private Entry(Object owner, Object callback, boolean recording) {
            this.owner = new WeakReference<>(owner); this.callback = new WeakReference<>(callback); this.recording = recording;
        }
        public Object wrapper() { return wrapper.get(); }
        public void screenshotDelivery(Runnable delivery) { screenshotDelivery = new WeakReference<>(delivery); }
        /** Called on the app's original executor, not on the runtime Binder/polling thread. */
        public synchronized int recordingValue(int state) {
            real = state; hasRealEvent = true;
            return recordingInitialValue(state);
        }
        public int recordingInitialValue(int state) {
            Object value = rules.apply(RECORDING);
            return value == null ? state : (Boolean)value ? 1 : 0;
        }
        public boolean allowScreenshot() { return !Boolean.FALSE.equals(rules.apply(SCREENSHOT)); }
        private void notifyApp(boolean screenshot) {
            Executor target = executor.get(); if (!active || target == null) return;
            try {
                target.execute(() -> {
                    if (!active || owner.get() == null) return;
                    Object original = callback.get(); if (original == null) return;
                    try {
                        if (screenshot) {
                            Runnable delivery = screenshotDelivery.get();
                            if (delivery != null && Boolean.TRUE.equals(rules.apply(SCREENSHOT))) delivery.run();
                        } else {
                            Object value = rules.apply(RECORDING);
                            @SuppressWarnings("unchecked") Consumer<Integer> consumer = (Consumer<Integer>)original;
                            consumer.accept(value == null ? real : (Boolean)value ? 1 : 0);
                        }
                    } catch (Throwable error) { errors.accept(error); }
                });
            } catch (RuntimeException error) { errors.accept(error); }
        }
    }
    public synchronized Entry find(Object owner, Object callback, boolean recording) {
        prune();
        for (Entry entry : entries) if (entry.owner.get() == owner && entry.callback.get() == callback && entry.recording == recording) return entry;
        return null;
    }
    public Entry create(Object owner, Object callback, boolean recording) { return new Entry(owner, callback, recording); }
    public void registered(Entry entry, Object wrapper, Executor executor, int realState) {
        synchronized (this) {
            prune(); entry.wrapper = new WeakReference<>(wrapper); entry.executor = new WeakReference<>(executor);
            // A direct/multithreaded executor may deliver a newer event before addCallback returns.
            synchronized (entry) { if (!entry.hasRealEvent) entry.real = realState; }
            entry.active = true; if (!entries.contains(entry)) entries.add(entry);
        }
        if (!entry.recording && Boolean.TRUE.equals(rules.apply(SCREENSHOT))) entry.notifyApp(true);
    }
    public synchronized void removed(Entry entry) {
        if (entry != null) { entry.active = false; entries.remove(entry); }
    }
    private void prune() {
        entries.removeIf(entry -> {
            if (entry.owner.get() != null && entry.callback.get() != null && entry.wrapper.get() != null) return false;
            entry.active = false; return true;
        });
    }
    public void changed(Object oldScreenshot, Object screenshot, Object oldRecording, Object recording) {
        List<Entry> current;
        synchronized (this) { prune(); current = new ArrayList<>(entries); }
        for (Entry entry : current) {
            if (entry.recording && !Objects.equals(oldRecording, recording)) entry.notifyApp(false);
            else if (!entry.recording && Boolean.TRUE.equals(screenshot) && !Boolean.TRUE.equals(oldScreenshot)) entry.notifyApp(true);
        }
    }
}
