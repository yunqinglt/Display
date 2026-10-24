# Display

PosEdMCP 的第二个辅助软件，目标是以 LSPosed 模块向作用域应用提供自定义 Android 框架返回值。

**0.4.0 已实现 Project UI、DisplayCore、原子持久化、框架作用域校验、运行端 AIDL 和系统框架钩子。** 当前支持现代 libxposed API 102（实测 Vector 2.2）；旧版经典 LSPosed/API 100 暂不支持。不进入 system_server。

源码位于 [yunqinglt/Display](https://github.com/yunqinglt/Display)，配套 MCP 位于 [yunqinglt/PosEdMCP](https://github.com/yunqinglt/PosEdMCP)。使用方法与实测截图见 [Project UI](docs/PROJECT-UI.md)，新增 30 组接口与截图/录屏语义见 [扩展接口](docs/EXPANDED-HOOKS.md)，钩子覆盖边界见 [后端说明](docs/BACKEND.md)。

| 模块 | 职责 |
| --- | --- |
| `display-api` | 控制/运行端 AIDL、JSON 信封、51 个规则键的校验 |
| `display-core-api` | DisplayCore / ProjectCore 端口与稳定错误类型 |
| `display` | Project UI、原子 Core、两类服务、作用域校验、现代模块及钩子 |
| PosEdMCP `display-api` | 独立编译所用的契约快照 |
| PosEdMCP `display/` | Binder 客户端、10 个 MCP 工具 |

阅读顺序：

1. [研究与架构决策](docs/RESEARCH.md)
2. [AIDL 协议及后端接入约束](docs/AIDL.md)
3. [测试与交付记录](docs/VALIDATION.md)
4. [示例配置](examples/travel-test.json)
5. [小米 15 实机接入与 HyperOS 自启动记录](docs/XIAOMI15-DEVICE.md)

构建需要 JDK 17–23、Android SDK 36；项目使用 Java 17 源码与 AGP 8.13.2。
在本机设置 `local.properties` 为 `sdk.dir=E\:/SDK`，然后执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-22'
.\gradlew.bat :display:assembleDebug :display:lintDebug
```

Display 与 PosEdMCP 必须使用同一签名证书。默认本机 debug 构建共用用户的 debug keystore；正式发布需给两端配置同一 release 签名。控制绑定需要 signature 级 `dev.display.permission.CONTROL`，服务还逐次校验 UID 对应包名和签名。运行通道由目标绑定服务并在工作线程同步；HyperOS 需允许辅助服务自启动。应用代码不调用 root、不开放网络端口。

`status.coreAvailable=true` 表示存储后端可用；还应检查 frameworkStatus.framework.connected、目标真实作用域、各进程 ACK 和实际值。capabilities 中 backendImplemented=true 为代码目录，runtimeSupported=null 表示需查具体进程。动态配置通常无需重启，但应用缓存可能需要刷新/重启；clearTarget 后新调用恢复原值。

实机已验证隐秘参数框架取值、六类传感器与 Edge 网页东京站定位。Edge 曾在整组配置下冷启动发生 native 崩溃，尚未定位到单条规则；当前仅定位配置通过，勿将整组示例直接用于 Edge。测试绑定已撤回，配置示例保留但未启用。

项目沿用远程仓库的 [GPL-3.0 许可证](LICENSE)。发布 APK 使用与 PosEdMCP 相同的开发签名，源码中的签名级 AIDL 权限保持不变；自行构建两端时也应使用同一证书。
