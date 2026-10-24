> 0.4.0 扩展接口、截图/录屏语义与本轮实测见 [EXPANDED-HOOKS.md](EXPANDED-HOOKS.md)。

> 以下为0.1.0前端阶段的历史记录。2026-10-22当前已推进至0.4.0，Project UI见 [PROJECT-UI.md](PROJECT-UI.md)，Core、框架钩子与定位实测见 [BACKEND.md](BACKEND.md)。

# 小米 15 实机接入记录

日期：2026-10-22。用户已重启，并将隐秘参数与 Microsoft Edge 加入 PosEdMCP 作用域。

## 已观察到的状态

- Android 15 / API 35；PosEdMCP 前台服务运行，无障碍服务已启用。
- `module_status.systemBridgeConnected=true`；在线 peer 包含 `com.x1y9.probe`（隐秘参数）和 `com.microsoft.emmx`（Edge）。这是 PosEdMCP 加载成功的证据，不代表 Display 已有 Hook。
- 对 `display_status` 的首次真实调用得到 `UNAVAILABLE: Display service unavailable`。
- 同时 HyperOS `WakePathChecker` 日志明确出现 `MIUILOG-AutoStart ... caller=dev.posedmcp callee=dev.display classname=dev.display.DisplayAidlService ... Reject`。
- 此时 Display 包状态为 installed=true、stopped=true、notLaunched=true，签名控制权限已授予 PosEdMCP。

结论：安装、签名授权与 Android 编译通过不能证明 Binder 已经可达。当前观察到的服务唤起阻断来自 HyperOS 自启动策略；已提示用户允许 Display/Deploy 后台自启动，再做实机链路回归。不能把这里的失败写成 Core 错误或 LSPosed 作用域失败。

用户随后确认已开启后台自启动。更新 PosEdMCP 客户端修正并重新打开目标应用后，**17 项实机检查全部通过**，原始非敏感结果保存在 [device-smoke.json](device-smoke.json)。确认实际 PosEdMCP → Binder → Display 的状态、能力、配置校验调用成功；Core 缺失相关读写均按预期返回工具错误，Deploy AIDL 同样可达。重新安装导致的目标桥短暂断线在恢复前台后已恢复，不需要重新选择作用域。

Android 15 的 stopped package 语义也需要纳入首次使用设计；当前工程没有 launcher，后续前端必须明确说明首次启动/ROM 自启动设置路径，而不是偷偷调整系统策略。[Android 15 package stopped state](https://developer.android.com/about/versions/15/behavior-changes-all)

## 前端修正与复现

PosEdMCP 的绑定失败提示补充了检查应用启用状态、HyperOS 后台自启动的指引。连接尝试在 bindService 返回 false 时也会释放 ServiceConnection，避免反复失败留下未释放连接。

该修正已重新构建并覆盖安装到小米 15；本次实机通过结果来自更新后的客户端。未修改系统 Hook 入口，也没有执行设备重启。

复现脚本位于 `E:/posedmcp/tools/test-display-device.ps1`：

```powershell
.\tools\test-display-device.ps1 -Serial <adb设备序列号> -OutputPath D:/Display/docs/device-smoke.json
```

脚本只在 `coreAvailable=false` 时执行后续检查，保证“写工具错误路径测试”不会向真实 Core 提交写入；token 仅存在内存，临时 ADB forward 在 finally 中释放。检查 10 个 MCP 工具、实际 Binder 配置校验、缺少 Core 的错误语义、两目标 peer 与 Deploy AIDL 可达性。

## 两个应用的后续验收分工

| 应用 | 本轮确认 | 后续 Core 验收 |
| --- | --- | --- |
| 隐秘参数 `com.x1y9.probe` | PosEdMCP peer 在线 | 时间、语言、网络、传感器、电池等逐项检查原始值、改写值和撤回后的值 |
| Edge `com.microsoft.emmx` | PosEdMCP 多进程 peer 在线 | 主进程与相关进程覆盖，浏览器 Geolocation 首次请求、缓存、watch、撤回与错误回调 |

用户打开的 [wheremylocation.com](https://wheremylocation.com/) 页面分别展示 IP Location 与 Browser Geolocation。前者可以来自公网出口 IP，后者通过浏览器定位请求取得坐标。修改 Android 定位返回值不等于修改公网 IP；验收必须分别记录来源，避免以 IP 城市不变判断 Hook 失败。

W3C Geolocation 定义 getCurrentPosition/watchPosition/clearWatch，以及 maximumAge 等缓存选项。Edge 页面结果还受网站权限、Android 定位权限、缓存与浏览器实现影响，不能仅测试一个 Location getter 后就宣称网页定位已覆盖。[W3C Geolocation](https://www.w3.org/TR/geolocation/)

Android LocationManager 有缓存位置、当前位置与位置更新接口。后续需要确认目标浏览器实际路径，并统一坐标、精度、时间、provider 与监听回调。[Android LocationManager](https://developer.android.com/reference/android/location/LocationManager)

定位规则目前仍未列入首版 20 个键的运行时实现；DisplayCore 全部未实现，因此本轮不注入临时 Lua 伪装来冒充 Display 的功能验证。不保存或公开用户的真实经纬度、IP 或位置截图。
