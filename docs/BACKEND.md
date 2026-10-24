# Display 0.4.0 后端

0.4.0 新增30组规则，当前共51组、12类别/63基础字段。截图事件/录屏状态、锁屏、音频、显示、设置、Wi-Fi与新增传感器的范围及本轮验证见 [EXPANDED-HOOKS.md](EXPANDED-HOOKS.md)。下文的0.2.x/0.3.0测试数字与绑定版本是历史记录。

## 框架与通信

实机为小米15 / Android15 API35 / Vector2.2(3112)，框架API102。使用 compileOnly libxposed API102 和 service101（向前兼容，SDK36可构建）；service102发布包要求SDK37，本实现无需其热重载接口。通过 META-INF/xposed 注册，无经典入口或系统进程注入。0.3.0增加[Project UI](PROJECT-UI.md)，与AIDL共用同一Core；钩子与0.2.3一致。

作用域来自框架 service.getScope()，不调用su、不编辑框架数据库、不接受运行端自报作用域。控制AIDL保留signature权限和PosEdMCP唯一UID/签名校验。独立 IDisplayRuntime 仅允许读取自己包的快照和提交报告，每次校验同用户、唯一UID包归属、框架实际作用域。任意应用可尝试绑定，但无法越权读配置或变更Core。

运行报告为作用域UID的合作式诊断，不是密码学证明。恶意目标能干预自己进程的报告，无法因此获得配置写权限。QUERY_ALL_PACKAGES用于校验用户任选的目标包，不把包名清单作为权限来源。

每个普通目标进程安装一次钩子，在Application.attach后用工作线程每秒交换快照/ACK。框架热路径只读volatile快照，不做Binder/磁盘I/O。10秒不能连通服务后运行端清空快照。被系统冻结的进程可能无法执行清空/报告：服务10秒标stale、60秒不再列出，解冻后重新同步。应用已缓存的旧值不会自动回滚。

## 原子存储与版本

私有 files/display-state.json 通过AtomicFile写入。单锁串行CAS，先复制/校验/落盘，成功才发布内存；磁盘失败不发布。损坏/未知格式拒绝打开，不覆盖原数据。配置、目标各128条（含墓碑），状态上限2M UTF-16字符；达到限制返回错误，不静默淘汰墓碑。

配置和目标分别版本化。目标保存选定配置的独立快照，更新同名配置不影响旧绑定；仍被引用的配置禁止删除。删除/clear保留递增版本的墓碑，防ABA。0仅表示从未创建，删除后重建须使用墓碑版本。

最近256个成功变更的requestId、规范参数SHA256和结果与状态一同落盘。对象键排序、数组顺序保留；同ID同参返回原结果，同ID异参返回REQUEST_ID_CONFLICT。失败不占ID。超过窗口无幂等保证，仍需正确CAS。请求成功保存目标只返回desiredUpdated=true、applied=false；实际使用display_runtime_state查询ACK。

运行状态包括真实scopeVerified、desiredRevision、每进程sessionId/pid、ackRevision、unsupportedRules、hookFailures和调用计数。计数不等价于值验证；backendImplemented是实现目录，runtimeSupported=null表示需查询运行进程。active仅表示当前ACK一致且未报告不支持规则，覆盖程度还需实际测试。

## 钩子覆盖边界

| 领域 | 实现 | 限制 |
| --- | --- | --- |
| 时间 | System.currentTimeMillis、Date()、Clock.currentInstant/Instant.now | 不改uptime/elapsedRealtime/传感器时间戳；不保证所有已内联/native/JS时钟路径 |
| 语言时区 | Locale、LocaleList默认值、Resources配置副本、TimeZone默认值 | 不下载语言资源、不回写旧缓存，不保证浏览器Intl全覆盖 |
| 网络 | ConnectivityManager、NetworkInfo、NetworkCapabilities常用getter和集合 | 仅框架视图，不改IP/路由/DNS，不合成连接回调或不存在的Network对象 |
| 传感器 | SensorEventQueue.dispatchSensorEvent | 复制数值数组再替换，保留时间戳/频率/精度；不覆盖NDK/direct channel、派生/uncalibrated类型，不创建缺失硬件 |
| 电池状态 | BatteryManager、BATTERY_CHANGED Intent extra/Bundle副本、PowerManager | 不改真实电量/充电/休眠；厂商扩展、其他parcel路径未保证覆盖 |
| 定位 | Location纬经度/精度getter、hasAccuracy、CREATOR反序列化副本、LocationManager缓存读取及当前位置/更新交付副本 | 不绕过权限、不切GPS、不生成首个fix、不改IP定位；不保证任意已内联调用者、GNSS/NMEA/其他辅助getter |

location.position新增到schemaVersion1：`{"latitude":35.681236,"longitude":139.767125,"accuracyMeters":12}`。纬度±90、经度±180、精度0.1至100000米，严格拒绝未知字段/非有限值。

getter先执行原方法，保留权限异常，无规则原结果直通。Date()完成后用框架origin invoker读取原墙上时间改写，避免ART intrinsic绕过/双重偏移；显式Date(long)不改。传感器适配HashMap/SparseArray内部容器，失败时移除该领域支持并报告。其他安装/执行失败保留hookFailures。

0.2.2补上定位对象交付边界。仅此仍未解决实机 Edge 网页：精度 getter 有调用，经纬度 getter 被绕过。0.2.3在 Application.attach 后查找可选 Chromium LocationProviderAdapter，按 static void(Location,...) 签名取消调用方优化，兼容发布包方法重命名；没有该类则跳过。不替换网页 JS 或浏览器 JNI 返回值，不另装浏览器定位 Hook。实机升级后用户确认网页显示35.681236、139.767125。Chromium转换层读取Location再传入JNI的依据见[官方源码](https://github.com/chromium/chromium/blob/main/services/device/geolocation/android/java/src/org/chromium/device/geolocation/LocationProviderAdapter.java)。这只是已知调用方兼容，不能推导任意应用内联路径全覆盖。

## 验证与资料

175项前端回归、26项Core回归覆盖并发CAS唯一赢家、去重冲突/重启、版本固定、引用删除保护、墓碑ABA、磁盘失败及定位边界。Display构建/Lint通过，保留3项有明确用途的警告（框架私有API、动态locale、按UID鉴权的导出服务）。

0.3.0另有101项Project回归，合计302项。UI配置与目标一次原子提交，并分别校验profile/target版本；主机与实机的UI读写、开关和撤回证据见[PROJECT-UI.md](PROJECT-UI.md)。以下0.2.x设备报告保留为当时记录，当前测试后目标状态为隐秘参数rev6、Edge rev4均未绑定。

隐秘参数最终0.2.3版本15项getter全部通过，见device-framework-probe.json。device-edge-framework-probe.json为早期0.2.0的15项检查，不能充当最终网页证据。最终真实6类传感器、定位getter/parcel内部字段及墙上时间/单调时间检查见device-sensor-clock-probe.json。变化型传感器可在注册时重放旧事件，时间戳判据为正值且不超出单调时钟，不强求最近15秒。测试DEX最多监听6秒且finally注销，最终探针不安装钩子；结束后已停止隐秘参数进程移除其类。排查中曾尝试PosEdMCP诊断Hook插件，因现有Hook桥类解析失败而未加载，未用其伪造成功结果。

device-edge-web-location-probe.json记录0.2.3前失败及升级后用户确认成功的证据来源；device-location-parcel-probe.json为Edge内部字段的人工对象测试。报告不保存真实坐标、IP、MCP凭据或原始传感器值。配置写入、插件、Lua与屏幕/输入操作走现有PosEdMCP确认窗；ADB用于安装、授权过的辅助服务自启动恢复、日志及目标进程重启。

收尾：隐秘参数目标rev2、Edge目标rev4均profileId=null；两端确认对应ACK且无Hook失败。device-restoration-probe.json确认墙上时间、人工定位对象及六类传感器撤回，device-edge-restoration-probe.json确认Edge人工定位getter恢复。两份配置保留为未绑定示例。已缓存对象/网页文本不会追溯改写，需刷新/重启。

## 已知未解决问题

Edge在20条混合规则启用后冷启动发生libchrome native SIGTRAP，触发自身错误收集Activity。撤回后可启动；目前只定位配置冷启动及网页请求成功。尚未隔离触发规则，不能声称0.2.3修复整组配置崩溃。默认配置脚本只向隐秘参数应用整组规则，Edge使用单独定位配置。未提交错误报告、未清除Edge用户数据。跨ROM、NDK传感器、完整浏览器Intl/JS时钟、连续watchPosition和无首个fix场景仍未验收。

官方依据：[libxposed API](https://github.com/libxposed/api)、[框架service](https://github.com/libxposed/service)、[Location](https://developer.android.com/reference/android/location/Location)、[SensorEvent](https://developer.android.com/reference/android/hardware/SensorEvent)、[SystemClock](https://developer.android.com/reference/android/os/SystemClock)。
