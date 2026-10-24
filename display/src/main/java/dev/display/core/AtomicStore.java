package dev.display.core;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

public final class AtomicStore implements ProfileCore.Store {
    private final AtomicFile file;
    public AtomicStore(File path) { file = new AtomicFile(path); }
    public String read() throws Exception {
        try { return new String(file.readFully(), StandardCharsets.UTF_8); }
        catch (FileNotFoundException e) { return null; }
    }
    public void write(String json) throws Exception {
        FileOutputStream stream = file.startWrite();
        try { stream.write(json.getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception e) { file.failWrite(stream); throw e; }
    }
}
