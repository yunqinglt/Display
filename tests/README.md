# 主机回归

需要 JDK 17+ 与 `org.json:json:20240303`，脚本检查 SHA-256。可从 Maven Central 下载到 `.tools/test-libs/json-20240303.jar`，或用参数指定已有文件：

```powershell
.\tests\run.ps1 -JsonJar E:/posedmcp/.tools/test-libs/json-20240303.jar
```

测试使用真实 JSON 库及纯Java Core，不向 APK 打包假后端。当前205项前端、26项Core、191项Project、96项Advanced检查，共518项。ProjectRegression覆盖基础值表单映射、组合字段完整性、UI/AIDL版本冲突、原子配置/开关、磁盘失败、关闭保留、引用删除保护和重启持久化。AdvancedRegression覆盖新增范围与回调身份、执行器、去重、恢复、注销取消和执行器失败。

这是主机测试，不覆盖 Android Binder、实际签名校验、LSPosed、ROM 或设备框架。Android APK 构建和 Lint 是独立验证步骤。真机验证计划见 `docs/RESEARCH.md`。

Project UI实机流程与截图见 `docs/PROJECT-UI.md`。`device/ProjectUiSession.ps1`是通过现有PosEdMCP确认策略读取控件、注入输入和截屏的辅助函数，需先加载`E:/posedmcp/tools/DisplayDeviceSession.ps1`并在finally关闭会话。没有绕过确认策略的ADB截屏或输入步骤。

0.4.0扩展验证见 `docs/EXPANDED-HOOKS.md`。`device/build.ps1`编译FrameworkProbe与AdvancedProbe测试DEX；`device/expanded-smoke.ps1`针对已勾选的隐秘参数验证新增取值、传感器和隔离框架回调，`device/edge-screenshot-smoke.ps1`仅向Edge临时应用单条截图事件规则并验证真实Activity注册。两脚本读取CAS版本、等待/检查同步，在finally撤回自己的绑定，不修改其他配置绑定。Edge计数只在登记过回调的进程返回，并核对PID一致，避免多进程的未初始化值冒充真实结果。
