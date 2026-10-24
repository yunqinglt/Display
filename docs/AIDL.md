# Display AIDL v1

> 当前为0.4.0，已有Core/钩子与[Project UI](PROJECT-UI.md)。UI使用进程内ProjectCore端口，控制AIDL事务0–10保持不变。契约目录扩展为51组规则，capabilities增加minimumSdk；取值、范围与事件语义见[EXPANDED-HOOKS.md](EXPANDED-HOOKS.md)。运行通道和覆盖边界见[BACKEND.md](BACKEND.md)。

端点：`dev.display/dev.display.DisplayAidlService`，接口：`dev.display.api.IDisplay`。
客户端必须声明可见包 `dev.display` 与 uses-permission `dev.display.permission.CONTROL`，使用显式 ComponentName、后台线程与同签名证书。
服务只接受自身 UID 或唯一所属包为 `dev.posedmcp` 的同签名 UID。

## 信封

```json
{"ok":true,"apiVersion":1,"data":{"name":"Display","coreAvailable":true}}
```

```json
{"ok":false,"apiVersion":1,"error":{"code":"CORE_UNAVAILABLE","message":"..."}}
```

所有回复包括ping都使用信封。status区分coreAvailable与frameworkStatus.framework.connected，不能据此推断目标已应用。
capabilities返回validationOnly=false、coreAvailable=true；backendImplemented=true为实现目录，runtimeSupported=null须查询目标进程，contractSupported=true仅表示契约支持。
validateProfile 成功时返回 valid=true、profile、runtimeVerified=false、persisted=false、applied=false。

## 方法

| AIDL ID（实际 Binder code 为 FIRST_CALL_TRANSACTION + ID） | 方法 | 语义 |
| --- | --- | --- |
| 0 | ping | 前端名称与版本 |
| 1 | status | 前端/Core 可用性及真实框架service连接状态 |
| 2 | capabilities | 规则契约目录与运行时支持区分 |
| 3 | validateProfile(profileJson) | 有界类型和值域校验，不写数据 |
| 4 | getProfile(profileId) | Core 返回 `{profile, revision}` |
| 5 | putProfile(profileJson, expectedRevision, requestId) | CAS 保存；0 创建，不自动应用 |
| 6 | deleteProfile(profileId, expectedRevision, requestId) | CAS 删除；拒绝仍被引用的 profile |
| 7 | getTarget(packageName) | Core 返回 `{package, profileId, profileRevision, revision}`，未绑定时 profileId=null、profileRevision=0；revision=0 表示从未有过绑定 |
| 8 | applyProfile(packageName, profileId, profileRevision, expectedTargetRevision, requestId) | 固定 profile revision，CAS 变更目标；必须验证真实 scope |
| 9 | clearTarget(packageName, expectedTargetRevision, requestId) | CAS 撤回绑定，发布恢复原值意图 |
| 10 | getRuntimeState(packageName) | Core 返回 scope 与每个进程实际 ACK，不能用配置存储代替 |

4–10均有Core实现。稳定错误包括NOT_FOUND、REVISION_CONFLICT、REQUEST_ID_CONFLICT、PROFILE_IN_USE、NOT_IN_SCOPE、FRAMEWORK_UNAVAILABLE、ENTRY_LIMIT和STORAGE_LIMIT。变更结果不确定时先查询；重试沿用requestId。

## Profile schemaVersion=1

```json
{"schemaVersion":1,"id":"demo","rules":[{"key":"state.batteryLevel","value":80}]}
```

根字段仅 schemaVersion/id/rules；规则字段仅 key/value。id/requestId 为 1–64 ASCII 字符，首字符字母或数字，后续允许字母、数字、`_ . -`。profile 最多 32768 UTF-16 code units，1–64 规则，不接受重复键、未知字段、未知规则键、null 值和字符串转数字/布尔。空规则不表示撤回，需调用 clearTarget。JSON 解码采用 Android org.json，Core 按递归规范化的数据比较重试参数，不把原始JSON文本作为去重依据。

| 规则键 | 值类型和值域 |
| --- | --- |
| location.position | 对象，latitude±90、longitude±180、accuracyMeters为0.1–100000；有限数字、必须三字段齐全且无额外字段 |
| time.wallOffsetMillis | 整数，±315576000000 ms，约 ±10 年 |
| time.wallFixedMillis | 整数，0–4102444800000 epoch ms；与 offset 互斥 |
| locale.languageTags | 1–8 个有效 BCP-47 字符串，每个 ≤64 字符，不允许规范化后重复 |
| locale.timeZone | java.time.ZoneId 可识别时区，例如 Asia/Shanghai；也接受 ZoneId 支持的 offset 形式 |
| network.connected / validated / metered | 布尔 |
| network.transport | none / wifi / cellular / ethernet / vpn 的单个场景标签 |
| sensors.accelerometer | 三轴有限数值，每轴绝对值 ≤200 m/s² |
| sensors.gyroscope | 三轴有限数值，每轴绝对值 ≤100 rad/s |
| sensors.magneticField | 三轴有限数值，每轴绝对值 ≤10000 μT |
| sensors.light | 单元素数组，0–200000 lux |
| sensors.pressure | 单元素数组，0–2000 hPa |
| sensors.proximity | 单元素数组，0–1000 cm |
| state.batteryLevel | 整数百分比 0–100 |
| state.batteryStatus | BatteryManager 整数常量 1–5 |
| state.batteryPlugged | 0 / 1 / 2 / 4 / 8，分别为电池/AC/USB/无线/Dock |
| state.powerSave / interactive / deviceIdle | 布尔 |

值域为项目契约，未声称对应任意设备物理量程。允许零气压等边界用于测试。未配置键的意图是保留原始值；Core 后续负责识别部分配置导致的组合矛盾。

当前网络标签没有 Wi-Fi SSID、IP、DNS、真实路由对象，传感器没有轨迹或频率参数。不能据此推导支持了整个领域全部接口。

## Core 接入的写入语义（0.2.3已实现）

- revision 为 0–9007199254740991 的 JSON 安全整数；apply 的 profileRevision 必须 >0。
- 创建用 expectedRevision=0；每次变更严格递增，撤回保留 revision tombstone，避免删除后 revision 重置造成 ABA。
- profile 更新不偷偷修改已绑定目标。目标固定 profileRevision；Core 保存被引用快照，或在删除前拒绝引用。读取最新 profile 后用指定 revision 应用；冲突需要调用方重新读取。
- requestId 在整个受信任控制域中唯一，同 ID 同方法同规范参数返回原始结果；同 ID 不同参数返回 REQUEST_ID_CONFLICT。去重记录与提交原子持久化。status公布最近256条成功变更的保留窗口；不允许客户端无限期重试。
- put 返回 `{profile, revision, persisted:true}`；delete 返回 `{profileId, revision, deleted:true}`。apply/clear 返回目标 binding、revision 和 desiredUpdated=true，不返回虚假的 applied=true。
- runtime 状态至少包含 package、scopeVerified、desiredRevision、processes；进程项包含 processName、pid、sessionId、ackRevision、state、unsupportedRules、restartRequired。sessionId 区分 PID 复用。
- 无在线进程应报告 processes=[]，并且不能宣称 applied。断线状态为 unknown/stale；Core 缺失时用错误，不能返回空列表冒充无活动进程。
- Scope 查询失败、未启用模块或范围外目标时拒绝 apply。AIDL 层包名格式校验不等于作用域校验。

DisplayAidlService已组合真实ProfileCore；CAS/持久化/去重由Core回归及真机分发验证。当前保留最近256个成功变更的去重结果，配置/目标各128条（含墓碑）。不能把APK构建成功当作所有框架路径有效。

## 错误与兼容

本轮服务错误码：UNAUTHORIZED、INVALID_ARGUMENT、CORE_UNAVAILABLE、CORE_ERROR、REPLY_TOO_LARGE。
客户端补充 UNAVAILABLE、INVALID_REPLY、VERSION_MISMATCH、TRANSPORT_ERROR。签名不匹配返回 UNAUTHORIZED；缺少应用可能与签名不可验证合并报告。
Binder 错误后不自动重试。调用结果可能未知，先读状态再用相同 requestId 查询/重试；不能换 ID 重放写入。

本轮只支持 apiVersion=1。AIDL 方法 ID、名称、参数次序/类型不改；添加方法用新 ID。JSON 可增加可选输出字段，调用方忽略未知输出；未知输入拒绝。改变旧规则的单位/语义需新 key 或新 schemaVersion。应用 AIDL 生成代码无 Stable AIDL 自动版本协商；兼容破坏需要两端同步升级。

回复不得超过 65536 UTF-16 code units。后端不得返回无限进程历史、传感器流或堆栈全文；需要分页时增加方法。
