package dev.display;

import dev.display.core.CoreException;
import dev.display.core.ProfileCore;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import org.json.JSONObject;

/** Only the framework supplies this Binder; no root subprocess or editable scope mirror. */
public final class FrameworkConnection implements ProfileCore.Scope, XposedServiceHelper.OnServiceListener {
    private volatile XposedService service;
    public void onServiceBind(XposedService value) { service = value; }
    public void onServiceDied(XposedService value) { if (service == value) service = null; }
    public java.util.List<String> packages() throws CoreException {
        XposedService current = service;
        if (current == null) throw new CoreException("FRAMEWORK_UNAVAILABLE", "Display 尚未连接框架，请启用模块后刷新");
        try {
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String pkg : current.getScope()) {
                try { result.add(dev.display.api.DisplayContract.target(pkg)); }
                catch (IllegalArgumentException ignored) { /* system/companion scope is not editable */ }
            }
            return result;
        } catch (RuntimeException e) { throw new CoreException("FRAMEWORK_UNAVAILABLE", "暂时无法读取模块作用域，请刷新重试"); }
    }
    public boolean contains(String pkg) throws CoreException {
        XposedService current = service;
        if (current == null) throw new CoreException("FRAMEWORK_UNAVAILABLE", "Enable Display in Vector/API 102 and restart its service");
        try { return current.getScope().contains(pkg); }
        catch (RuntimeException e) { throw new CoreException("FRAMEWORK_UNAVAILABLE", "Cannot verify Display scope with framework service"); }
    }
    public JSONObject describe() throws Exception {
        XposedService current = service;
        if (current == null) return new JSONObject().put("connected", false);
        try { return new JSONObject().put("connected", true).put("name", current.getFrameworkName())
                .put("version", current.getFrameworkVersion()).put("api", current.getApiVersion()); }
        catch (RuntimeException e) { return new JSONObject().put("connected", false); }
    }
}
