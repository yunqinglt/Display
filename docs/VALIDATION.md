> 0.4.0 扩展接口、截图/录屏语义与本轮实测见 [EXPANDED-HOOKS.md](EXPANDED-HOOKS.md)。

> 以下为0.1.0前端阶段的历史记录。2026-10-22当前已推进至0.4.0，Project UI见 [PROJECT-UI.md](PROJECT-UI.md)，Core、框架钩子与定位实测见 [BACKEND.md](BACKEND.md)。

# 本轮验证与安装记录

日期：2026-10-22（Asia/Shanghai）。范围：充分研究、Display AIDL 前端及 PosEdMCP 两端接入；DisplayCore 与 Project UI 留后续。

## 已验证

- 主机回归 `tests/run.ps1`：174 项检查通过，包括全部 Core 端口的缺失错误、输入边界、并发校验、错误信封/版本识别、回复上限与 Core 路由。
- Display：`:display:assembleDebug :display:lintDebug` 通过，最终 Lint 为 `No issues found`。
- PosEdMCP：`:app:assembleDebug :app:lintDebug` 任务完成，APK 打包成功。项目配置为 abortOnError=false，Lint 报告仍包含两个未改动文件中的问题：local.properties 路径转义（PropertyEscape）和 app_name 中文翻译（MissingTranslation）；不能把任务成功解读为零 Lint 错误。
- Deploy：`:deploy:assembleDebug` 通过，使用现有 0.4.0 后端源码，无本轮功能改动。
- 两份 display-api 的 src 与 build.gradle.kts 逐文件一致；生成 AIDL 确认 transaction ID 为 FIRST_CALL_TRANSACTION + 0..10。
- 三个 APK 经 apksigner 校验并确认同证书，SHA-256 为 `a91bdefe3ae18fb0db4d93494d9cd04e9923eaaf5c781ac5b816897248028a71`。
- 用户授权安装后，通过 USB ADB 在小米 15（24129PN74C，Android 15/API 35）依次安装 Display、PosEdMCP、Deploy，均返回 Success。
- 安装后通过 pm path / dumpsys package 核对三个包与版本，并确认 PosEdMCP 的 `dev.display.permission.CONTROL: granted=true`。
- 用户选择作用域并重启后，系统桥、隐秘参数和 Edge 的 PosEdMCP peer 均确认在线。首次实机绑定发现 HyperOS 自启动拦截，用户放行后，17 项端到端设备检查全部通过，包括实际 Binder 状态/能力/校验调用及 Deploy AIDL。详情见 [小米 15 实机记录](XIAOMI15-DEVICE.md)与 [JSON 结果](device-smoke.json)。

| 软件 | 包名 | 版本 | 本机 APK |
| --- | --- | --- | --- |
| PosEdMCP（奈何桥） | dev.posedmcp | 1.1.5 / code 17 | E:/posedmcp/app/build/outputs/apk/debug/app-debug.apk |
| Deploy | dev.deploy | 0.4.0 / code 4 | E:/deploy/deploy/build/outputs/apk/debug/deploy-debug.apk |
| Display | dev.display | 0.1.0 / code 1 | D:/Display/display/build/outputs/apk/debug/display-debug.apk |

## 构建环境说明

JDK 22，Android compileSdk 36，AGP 8.13.2。实际成功验证使用现有 Gradle 8.14.3：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-22'
& E:/posedmcp/.tools/gradle-8.14.3/bin/gradle.bat -p D:/Display --gradle-user-home E:/posedmcp/.gradle-home :display:assembleDebug :display:lintDebug --offline
& E:/posedmcp/.tools/gradle-8.14.3/bin/gradle.bat -p E:/posedmcp --gradle-user-home E:/posedmcp/.gradle-home :app:assembleDebug :app:lintDebug --offline
& E:/posedmcp/.tools/gradle-8.14.3/bin/gradle.bat -p E:/deploy --gradle-user-home E:/posedmcp/.gradle-home :deploy:assembleDebug --offline
```

初次 Gradle 8.13 在本机出现缓存目录 rename 错误，另一缓存缺少 offline 依赖；换用工作区已有工具链与缓存后成功，未删除共享缓存。Display 自带 wrapper 仍为 8.13，符合 AGP 最低版本要求；上述命令记录本机实际成功路径。

## 尚未验证/实现

已通过在线 peer 确认 PosEdMCP 在两个目标进程内加载，已进行跨进程 AIDL 调用的设备测试。尚未测试 Display 框架伪装、真实配置存储/分发、设备杀进程后的重连或跨 ROM 兼容；这些不能由 AIDL 调用成功推导。

Core 未实现，因此没有存储、去重、revision CAS、作用域检测、分发、进程 ACK 或实际规则生效。当前 Display APK 无 launcher，也没有 Xposed module 入口；这是本轮前端交付，不应在 LSPosed 列表中寻找 Display。

用户已在 LSPosed 中选择作用域并重启，本轮确认隐秘参数与 Edge peer 在线。Deploy 是普通辅助应用，无需勾选作用域。没有替用户更改作用域或重启。
