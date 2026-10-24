package dev.display.core;

/** Stable, transport-neutral domain failure. */
public final class CoreException extends Exception {
    public final String code;
    public CoreException(String code, String message) { super(message); this.code = code; }
}
