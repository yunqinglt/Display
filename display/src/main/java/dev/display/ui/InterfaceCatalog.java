package dev.display.ui;

import dev.display.api.DisplayContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Primitive editor definitions. Object/array construction stays behind the form. */
public final class InterfaceCatalog {
    private InterfaceCatalog() {}
    public enum Kind { INTEGER, NUMBER, TEXT, BOOLEAN, CHOICE }
    public record Category(String id, String title, String description) {}
    public record Field(String title, String api, String type, String hint, Kind kind) {}
    public record Spec(String key, String category, String title, List<Field> fields,
                       String[] choices, String[] choiceLabels) {
        public boolean languages() { return key.equals("locale.languageTags"); }
    }
    public static final List<Category> CATEGORIES = List.of(
            new Category("time", "时间", "墙上时间与时间偏移"),
            new Category("locale", "语言与时区", "语言顺序、默认时区"),
            new Category("network", "网络", "连接状态、计费与传输类型"),
            new Category("location", "定位", "纬度、经度与定位精度"),
            new Category("sensors", "传感器", "三轴数值与环境传感器"),
            new Category("battery", "电池", "电量、充电状态与电源类型"),
            new Category("state", "设备状态", "省电、热状态与空闲模式"),
            new Category("capture", "截图与录屏", "截图事件、应用在录屏中的可见状态"),
            new Category("security", "锁屏状态", "锁屏显示与安全锁设置的查询结果"),
            new Category("audio", "音频", "播放、静音、音量与通话模式"),
            new Category("display", "显示", "旋转、刷新率与显示器状态"),
            new Category("settings", "系统设置", "飞行模式、旋转、亮度与时间格式"));
    private static Field field(String title, String api, String type, String hint, Kind kind) {
        return new Field(title, api, type, hint, kind);
    }
    private static Spec one(String key, String category, String title, String api, String type, String hint, Kind kind) {
        return new Spec(key, category, title, List.of(field(title, api, type, hint, kind)), new String[0], new String[0]);
    }
    private static Spec choice(String key, String category, String title, String api, String type, String[] values, String[] labels) {
        return new Spec(key, category, title, List.of(field(title, api, type, "", Kind.CHOICE)), values, labels);
    }
    private static Spec vector(String key, String title, String unit, String limit) {
        List<Field> fields = new ArrayList<>();
        for (String axis : new String[]{"X", "Y", "Z"}) fields.add(field(title + " " + axis + " 轴",
                "SensorEvent.values[" + fields.size() + "]", "float", limit + " " + unit, Kind.NUMBER));
        return new Spec(key, "sensors", title, List.copyOf(fields), new String[0], new String[0]);
    }
    public static final List<Spec> SPECS = List.of(
            one("time.wallOffsetMillis", "time", "时间偏移", "System.currentTimeMillis() · 偏移", "long", "−315576000000 ～ 315576000000 毫秒", Kind.INTEGER),
            one("time.wallFixedMillis", "time", "固定时间", "System.currentTimeMillis() · 固定值", "long", "0 ～ 4102444800000，Unix 毫秒", Kind.INTEGER),
            one("locale.timeZone", "locale", "默认时区", "TimeZone.getDefault() · 时区 ID", "String", "例如 Asia/Shanghai", Kind.TEXT),
            one("locale.languageTags", "locale", "语言顺序", "Locale / LocaleList · 语言标签", "String", "例如 zh-CN，每项一个语言标签", Kind.TEXT),
            one("network.connected", "network", "已连接", "NetworkInfo.isConnected()", "boolean", "", Kind.BOOLEAN),
            one("network.validated", "network", "网络已验证", "NetworkCapabilities · VALIDATED", "boolean", "", Kind.BOOLEAN),
            one("network.metered", "network", "按流量计费", "ConnectivityManager.isActiveNetworkMetered()", "boolean", "", Kind.BOOLEAN),
            choice("network.transport", "network", "传输类型", "NetworkCapabilities · transport", "String",
                    new String[]{"none", "wifi", "cellular", "ethernet", "vpn"}, new String[]{"none · 无", "wifi · Wi-Fi", "cellular · 蜂窝网络", "ethernet · 以太网", "vpn · VPN"}),
            new Spec("location.position", "location", "位置", List.of(
                    field("纬度", "Location.getLatitude()", "double", "−90 ～ 90 度", Kind.NUMBER),
                    field("经度", "Location.getLongitude()", "double", "−180 ～ 180 度", Kind.NUMBER),
                    field("定位精度", "Location.getAccuracy()", "float", "0.1 ～ 100000 米", Kind.NUMBER)), new String[0], new String[0]),
            vector("sensors.accelerometer", "加速度", "m/s²", "−200 ～ 200"),
            vector("sensors.gyroscope", "陀螺仪", "rad/s", "−100 ～ 100"),
            vector("sensors.magneticField", "磁场", "μT", "−10000 ～ 10000"),
            one("sensors.light", "sensors", "环境光", "SensorEvent.values[0] · LIGHT", "float", "0 ～ 200000 lux", Kind.NUMBER),
            one("sensors.pressure", "sensors", "气压", "SensorEvent.values[0] · PRESSURE", "float", "0 ～ 2000 hPa", Kind.NUMBER),
            one("sensors.proximity", "sensors", "距离", "SensorEvent.values[0] · PROXIMITY", "float", "0 ～ 1000 cm", Kind.NUMBER),
            one("state.batteryLevel", "battery", "电量百分比", "BatteryManager · CAPACITY", "int", "0 ～ 100 %", Kind.INTEGER),
            choice("state.batteryStatus", "battery", "充电状态", "BatteryManager · STATUS", "int",
                    new String[]{"1", "2", "3", "4", "5"}, new String[]{"1 · 未知", "2 · 充电中", "3 · 放电中", "4 · 未充电", "5 · 已充满"}),
            choice("state.batteryPlugged", "battery", "电源类型", "BATTERY_CHANGED · plugged", "int",
                    new String[]{"0", "1", "2", "4", "8"}, new String[]{"0 · 未接入", "1 · 交流电", "2 · USB", "4 · 无线", "8 · 底座"}),
            one("state.powerSave", "state", "省电模式", "PowerManager.isPowerSaveMode()", "boolean", "", Kind.BOOLEAN),
            one("state.interactive", "state", "可交互状态", "PowerManager.isInteractive()", "boolean", "", Kind.BOOLEAN),
            one("state.deviceIdle", "state", "设备空闲", "PowerManager.isDeviceIdleMode()", "boolean", "", Kind.BOOLEAN),
            one("capture.screenshotEvent", "capture", "截图事件", "Activity.ScreenCaptureCallback.onScreenCaptured()", "boolean",
                    "Android 14+；true 在注册或从未设置/false 切换为 true 时模拟一次事件；false 屏蔽事件。不是持续状态，也不会实际截图。需应用有检测权限并注册回调。", Kind.BOOLEAN),
            one("capture.recordingVisible", "capture", "应用正在被录屏", "WindowManager.addScreenRecordingCallback() · 返回值与回调", "boolean",
                    "Android 15+；true=应用在录屏中可见，false=不可见。需应用有检测权限并注册回调；不会启动录屏。", Kind.BOOLEAN),
            one("security.keyguardLocked", "security", "锁屏正在显示", "KeyguardManager.isKeyguardLocked()", "boolean", "", Kind.BOOLEAN),
            one("security.deviceLocked", "security", "设备已锁定", "KeyguardManager.isDeviceLocked()", "boolean", "", Kind.BOOLEAN),
            one("security.keyguardSecure", "security", "锁屏或 SIM 安全锁", "KeyguardManager.isKeyguardSecure()", "boolean", "仅改写查询结果", Kind.BOOLEAN),
            one("security.deviceSecure", "security", "已设置安全锁屏", "KeyguardManager.isDeviceSecure()", "boolean", "仅改写查询结果", Kind.BOOLEAN),
            choice("audio.ringerMode", "audio", "响铃模式", "AudioManager.getRingerMode()", "int",
                    new String[]{"0", "1", "2"}, new String[]{"0 · 静音", "1 · 振动", "2 · 正常"}),
            choice("audio.mode", "audio", "音频模式", "AudioManager.getMode()", "int",
                    new String[]{"0", "1", "2", "3"}, new String[]{"0 · 正常", "1 · 铃声", "2 · 通话", "3 · 通信"}),
            one("audio.musicActive", "audio", "音乐正在播放", "AudioManager.isMusicActive()", "boolean", "", Kind.BOOLEAN),
            one("audio.microphoneMute", "audio", "麦克风静音", "AudioManager.isMicrophoneMute()", "boolean", "仅改写查询结果，不切换麦克风", Kind.BOOLEAN),
            one("audio.speakerphoneOn", "audio", "扬声器已开启", "AudioManager.isSpeakerphoneOn()", "boolean", "", Kind.BOOLEAN),
            one("audio.musicVolumePercent", "audio", "媒体音量百分比", "AudioManager.getStreamVolume(STREAM_MUSIC)", "int", "0 ～ 100，映射到该音频流实际音量索引范围", Kind.INTEGER),
            one("audio.alarmVolumePercent", "audio", "闹钟音量百分比", "AudioManager.getStreamVolume(STREAM_ALARM)", "int", "0 ～ 100，映射到该音频流实际音量索引范围", Kind.INTEGER),
            choice("display.rotation", "display", "屏幕旋转", "Display.getRotation()", "int",
                    new String[]{"0", "1", "2", "3"}, new String[]{"0 · 0°", "1 · 90°", "2 · 180°", "3 · 270°"}),
            one("display.refreshRate", "display", "显示器刷新率", "Display.getRefreshRate() / Display.Mode.getRefreshRate()", "float", "1 ～ 1000 Hz；仅查询结果", Kind.NUMBER),
            choice("display.state", "display", "显示器状态", "Display.getState()", "int", new String[]{"0", "1", "2", "3", "4", "5", "6"},
                    new String[]{"0 · 未知", "1 · 关闭", "2 · 开启", "3 · 低功耗显示", "4 · 低功耗暂停", "5 · VR", "6 · 开启但暂停"}),
            choice("state.thermalStatus", "state", "热状态", "PowerManager.getCurrentThermalStatus()", "int", new String[]{"0", "1", "2", "3", "4", "5", "6"},
                    new String[]{"0 · 正常", "1 · 轻微", "2 · 中等", "3 · 严重", "4 · 临界", "5 · 紧急", "6 · 关闭"}),
            one("state.lightDeviceIdle", "state", "轻度设备空闲", "PowerManager.isDeviceLightIdleMode()", "boolean", "Android 13+", Kind.BOOLEAN),
            one("settings.airplaneMode", "settings", "飞行模式设置", "Settings.Global · airplane_mode_on", "boolean", "仅查询结果，不切换无线网络", Kind.BOOLEAN),
            one("settings.autoRotate", "settings", "自动旋转设置", "Settings.System · accelerometer_rotation", "boolean", "", Kind.BOOLEAN),
            one("settings.time24Hour", "settings", "24 小时制", "DateFormat.is24HourFormat() / Settings.System · time_12_24", "boolean", "", Kind.BOOLEAN),
            one("settings.brightness", "settings", "屏幕亮度设置", "Settings.System · screen_brightness", "int", "0 ～ 255，仅设置查询，不改变物理亮度", Kind.INTEGER),
            one("network.wifiEnabled", "network", "Wi-Fi 已开启", "WifiManager.isWifiEnabled() / getWifiState()", "boolean", "", Kind.BOOLEAN),
            one("network.wifiRssi", "network", "Wi-Fi 信号强度", "WifiInfo.getRssi()", "int", "−127 ～ 0 dBm", Kind.INTEGER),
            one("network.wifiLinkSpeed", "network", "Wi-Fi 链路速度", "WifiInfo.getLinkSpeed()", "int", "−1 ～ 100000 Mbps，−1 表示未知", Kind.INTEGER),
            vector("sensors.linearAcceleration", "线性加速度", "m/s²", "−200 ～ 200"),
            vector("sensors.gravity", "重力", "m/s²", "−200 ～ 200"),
            one("sensors.ambientTemperature", "sensors", "环境温度", "SensorEvent.values[0] · AMBIENT_TEMPERATURE", "float", "−273.15 ～ 1000 °C，需设备有此传感器", Kind.NUMBER),
            one("sensors.relativeHumidity", "sensors", "相对湿度", "SensorEvent.values[0] · RELATIVE_HUMIDITY", "float", "0 ～ 100 %，需设备有此传感器", Kind.NUMBER),
            one("sensors.stepCounter", "sensors", "累计步数", "SensorEvent.values[0] · STEP_COUNTER", "float", "0 ～ 1000000000000，保留原权限和事件频率", Kind.NUMBER));

    public static Spec find(String key) {
        for (Spec spec : SPECS) if (spec.key.equals(key)) return spec;
        throw new IllegalArgumentException("未知接口");
    }
    public static Object value(JSONArray rules, String key) {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule != null && key.equals(rule.optString("key"))) return rule.opt("value");
        }
        return null;
    }
    public static List<String> values(Spec spec, Object value) throws Exception {
        List<String> fields = new ArrayList<>();
        if (value == null) return fields;
        if (spec.key.equals("location.position")) {
            JSONObject o = (JSONObject)value;
            for (String key : new String[]{"latitude", "longitude", "accuracyMeters"}) fields.add(o.get(key).toString());
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int i = 0; i < array.length(); i++) fields.add(array.get(i).toString());
        } else fields.add(value.toString());
        return fields;
    }
    private static Object primitive(Field field, String raw) {
        String text = raw.trim();
        try {
            return switch (field.kind) {
                case INTEGER -> {
                    if (!text.matches("-?[0-9]+")) throw new IllegalArgumentException();
                    yield Long.parseLong(text);
                }
                case NUMBER -> {
                    double value = Double.parseDouble(text);
                    if (!Double.isFinite(value) || text.matches(".*[dDfF].*")) throw new IllegalArgumentException();
                    yield value;
                }
                case BOOLEAN -> {
                    if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException();
                    yield Boolean.valueOf(text);
                }
                case CHOICE -> field.type.equals("int") ? Long.valueOf(text) : text;
                case TEXT -> { if (text.isEmpty()) throw new IllegalArgumentException(); yield text; }
            };
        } catch (RuntimeException e) { throw new IllegalArgumentException(field.title + "：请输入有效的 " + field.type + " 值"); }
    }
    public static Object parse(Spec spec, List<String> raw) throws Exception {
        Object result;
        if (spec.languages()) {
            JSONArray tags = new JSONArray();
            for (String text : raw) if (!text.trim().isEmpty()) tags.put(primitive(spec.fields.get(0), text));
            result = tags;
        } else {
            if (raw.size() != spec.fields.size()) throw new IllegalArgumentException("请补齐全部字段");
            List<Object> fields = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) fields.add(primitive(spec.fields.get(i), raw.get(i)));
            if (spec.key.equals("location.position")) result = new JSONObject().put("latitude", fields.get(0))
                    .put("longitude", fields.get(1)).put("accuracyMeters", fields.get(2));
            else if (spec.key.startsWith("sensors.")) result = new JSONArray(fields);
            else result = fields.get(0);
        }
        try { DisplayContract.validateProfile(new JSONObject().put("schemaVersion", 1).put("id", "editor")
                .put("rules", new JSONArray().put(new JSONObject().put("key", spec.key).put("value", result))).toString()); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(spec.languages() ? "请输入 1–8 个不重复的有效语言标签，例如 zh-CN"
                    : "数值超出范围或格式无效，请检查字段提示");
        }
        return result;
    }
    /** Editing a wall clock mode replaces the mutually exclusive mode in the same transaction. */
    public static JSONArray replace(JSONArray rules, String key, Object value) throws Exception {
        JSONArray next = new JSONArray();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i); String existing = rule.getString("key");
            if (existing.equals(key)) continue;
            if (value != null && key.startsWith("time.wall") && existing.startsWith("time.wall")) continue;
            next.put(new JSONObject(rule.toString()));
        }
        if (value != null) next.put(new JSONObject().put("key", key).put("value", value));
        return next;
    }
}
