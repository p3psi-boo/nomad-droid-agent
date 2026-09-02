# Nomad Droid 使用指南

Nomad Droid 将 Android 设备接入 Nomad 集群，并以内置任务驱动运行两类工作负载：

| 驱动 | 用途 | 执行身份 |
| --- | --- | --- |
| `android` | 安装 APK，以及检查、启动和停止指定的 Android Service | Job 选择的 Shizuku `shell`/`root` 或直接 `su` root 用户 |
| `termux` | 在 Termux 环境中执行普通命令 | Termux 应用用户 |

两类驱动相互独立。`termux` 驱动不会通过 Shizuku 或 root 执行命令；Shizuku Broker 和 root 适配都不接受任意 Shell，只提供 APK 和 Android Service 生命周期所需的固定操作。

## 1. 使用前准备

### Android 设备

- Android 12 或更高版本；
- ARM64 架构；
- 能够访问 Nomad Server 的 RPC 端口；
- 使用 `android` 驱动并且未填写 `privilege` 或将 `privilege` 设为 `shizuku` 时，安装并启动 Shizuku；
- 使用 `android` 驱动并将 `privilege` 设为 `root` 时，设备需要提供可授权给 Nomad Droid 的 `su`；
- 使用 `termux` 驱动时，安装 Termux `0.109` 或更高版本。

`android` 驱动只需配置 Job 选择的 Shizuku 或直接 Root 权限。`termux` 驱动只需配置 Termux。

### Nomad Server

准备一个设备可访问的、未启用 TLS 的 Nomad RPC 地址，例如：

```text
10.0.0.10:4647
```

IPv6 地址必须写成：

```text
[2001:db8::10]:4647
```

除非 Nomad Server 就运行在手机上，否则不要填写 `127.0.0.1:4647`。请确认设备到该地址的 TCP `4647` 端口可达。

如果 Nomad 集群要求客户端引入认证，还需要准备 Client Introduction Token。应用中的该字段不是执行 `nomad` CLI 时使用的普通 ACL Token。

## 2. 构建并安装 APK

构建环境要求：

- JDK 17 或更高版本；
- Android SDK 36；
- Android NDK `28.2.13676358`；
- Go 1.26。

设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 后，在项目根目录执行：

```sh
./gradlew testDebugUnitTest assembleDebug
```

构建过程会先将 Go 代码交叉编译为 Android ARM64 共享库，再将其打包进 APK。输出文件位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

通过 ADB 安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装完成后打开 **Nomad Droid**。首次启动时，请允许通知权限；前台服务会使用常驻通知显示运行状态。

## 3. 配置 Shizuku

本节仅用于 `android` 驱动中未填写 `privilege` 或将 `privilege` 设为 `shizuku` 的任务。

1. 安装 Shizuku，并按照 Shizuku 应用内的说明通过无线调试、ADB 或 Root 启动服务。
2. 打开 Nomad Droid。
3. 在 **Shizuku** 区域点击 **Grant access**。
4. 在系统授权窗口中允许 Nomad Droid 使用 Shizuku。
5. 点击 **Connect broker**。

连接成功后，状态中应同时出现：

```text
permission=granted · broker=connected
```

状态还会显示 Shizuku 和 Broker 的实际 UID，便于确认任务以 `shell` 还是 `root` 身份执行。

设备重启后，Nomad Droid 可以恢复 Nomad Client，但 Shizuku 本身也必须重新可用。在 Shizuku Binder 恢复前，如果直接 Root 权限也未就绪，节点可以重新连接，但 `android` 驱动会保持不健康状态。

## 4. 配置直接 Root 权限

本节仅用于 `android` 驱动中将 `privilege` 设为 `root` 的任务。直接 Root 权限指 Nomad Droid 调用设备提供的 `su`，并以 UID `0` 执行固定的 APK 和 Android Service 生命周期命令。

1. 打开 Nomad Droid。
2. 在 **Root** 区域点击 **Check / grant root access**。
3. 在设备的 root 权限管理页面中允许 Nomad Droid 使用 `su`。
4. 确认状态中出现：

```text
Root access is ready · su=available · uid=0 · permission=granted
```

需要使用 root 的 Job 必须明确配置：

```hcl
config {
  privilege = "root"
  package   = "com.example.workload"
  service   = ".NomadWorkService"
}
```

没有填写 `privilege` 时，`android` 驱动继续使用 `shizuku`。root 适配只接受结构化的包名、Service 名、APK 路径和 SHA-256，不提供任意 Shell 字段。

如果 Nomad 集群还包含未启用直接 Root 权限的 Android 设备，root Job 需要增加节点约束：

```hcl
constraint {
  attribute = "${attr.driver.android.root}"
  value     = "true"
}
```

设备重启后，如果 root 权限管理程序保留了授权，Nomad Droid 会重新检查 UID。授权被撤销时，root 任务会失败，节点属性 `driver.android.root` 会恢复为 `false`。

## 5. 配置 Termux Shell

本节仅用于 `termux` 驱动。

### 5.1 允许外部应用调用 Termux

在 Termux 中编辑：

```text
~/.termux/termux.properties
```

确保文件中存在：

```properties
allow-external-apps=true
```

可以在 Termux 中检查：

```sh
grep '^allow-external-apps=' ~/.termux/termux.properties
```

预期输出：

```text
allow-external-apps=true
```

### 5.2 授予 RUN_COMMAND 权限

1. 打开 Nomad Droid。
2. 在 **Termux shell** 区域点击 **Grant access**。
3. 允许 **Run commands in Termux environment** 权限。

如果系统没有弹出权限窗口，请打开 Android 的应用详情页，进入 **Nomad Droid → 权限 → 其他权限**，手动允许该权限。也可以点击 **Termux app settings / install** 打开 Termux 的应用详情页或安装页面。

### 5.3 测试配置

点击 **Test setup**。测试完成后，状态中应出现：

```text
permission=granted · service=available · setup=ready
```

只有测试成功后，`termux` 驱动才会向 Nomad 报告为健康状态。

## 6. 配置保活

Nomad Client 需要持续保持 RPC 会话和心跳，不能依赖推送消息代替。因此，在启动 Agent 前完成以下设置：

1. 在 **Keep alive** 区域点击 **Battery settings**。
2. 允许 Nomad Droid 忽略电池优化。
3. 如果使用 Termux，在厂商的电池或后台管理页面中同时将 Termux 设为不受限制。
4. 保留 Nomad Droid 的前台服务通知。

Agent 运行时，应用会：

- 启动 `specialUse` 前台服务；
- 持有 CPU Partial Wake Lock，使熄屏后的定时器和心跳仍可运行；
- 使用 `START_STICKY` 请求系统在进程被回收后重建服务；
- 持久化用户选择的运行状态；
- 在设备启动完成或 APK 更新后恢复 Agent；
- 在 Shizuku Binder 失效后清理旧连接，并在 Shizuku 可用时重新绑定。

点击 **Stop** 会先关闭自动恢复，再停止 Agent。Android 的“强行停止”或部分厂商的“受限制”后台策略会阻止自动恢复；出现这种情况后，需要重新打开 Nomad Droid。

Termux 运行在另一个应用进程中，Nomad Droid 持有的 Wake Lock 不能阻止系统或厂商策略回收 Termux。因此，运行 `termux` 任务时也要单独配置 Termux 的电池策略。

## 7. 启动 Nomad Client

在 **Nomad client** 区域填写：

| 字段 | 填写方式 |
| --- | --- |
| **Nomad server RPC address** | `host:port` 或 `[IPv6]:port`，例如 `10.0.0.10:4647` |
| **Node name** | 以字母或数字开头，可包含字母、数字、点、下划线和连字符，例如 `pixel-01` |
| **Datacenter** | 可包含字母、数字、下划线和连字符；示例任务使用 `android` |
| **Client introduction token** | 集群要求客户端引入认证时填写，否则留空 |

点击 **Start agent**。启动成功后：

- Agent 状态显示 `Running`；
- **Last result** 显示启动结果；
- 通知栏显示 Nomad Droid 正在运行；
- **Keep alive** 状态显示 `restore=enabled`。

运行期间修改输入框不会热加载配置。需要应用新配置时，先点击 **Stop**，再点击 **Start agent**。

## 8. 在 Nomad Server 上确认节点

在已配置 Nomad CLI 的机器上执行：

```sh
nomad node status
```

找到手机节点的 ID 后查看详情：

```sh
nomad node status -verbose <node-id>
```

预期结果：

- 节点状态为 `ready`；
- Node Class 为 `android`；
- 节点属性包含 `nomad.droid.base = true`；
- 节点属性 `driver.android.shizuku` 和 `driver.android.root` 显示两种特权实现是否可用；
- 已配置的 `android` 或 `termux` 驱动为健康状态。

Nomad CLI 通常连接 Nomad HTTP API；Nomad Droid 输入框连接的是 Server RPC 地址。两者的地址和端口用途不同。

## 9. 运行 Android Service 任务

示例文件：[`examples/android-service.nomad.hcl`](examples/android-service.nomad.hcl)

### 8.1 准备工作负载 APK

目标 APK 中的 Service 必须满足以下条件：

1. 在 Manifest 中声明为可导出，因为它由 Shizuku 的 `shell` 或 `root` 进程启动；
2. 被启动后及时调用 `startForeground(...)`；
3. 声明匹配的前台服务权限和类型；
4. 运行期间显示自己的常驻通知。

示例中的下载地址、包名、Service 类名和 SHA-256 都是占位符，不能直接运行。请修改以下配置：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `package` | 是 | Android Application ID，例如 `com.example.workload` |
| `service` | 是 | Service 类名，例如 `.NomadWorkService` |
| `install` | 否 | 启动前是否安装 APK，默认 `true` |
| `apk_path` | 安装时 | APK 在 Nomad Allocation 目录内的路径 |
| `sha256` | 安装时 | APK 的小写 SHA-256 摘要 |
| `replace` | 否 | 安装时是否向 `pm install` 传入 `-r`，默认 `true` |
| `privilege` | 否 | `shizuku` 或 `root`；未填写时使用 `shizuku` |

`artifact.source` 必须是手机可以访问的下载地址。修改后执行：

```sh
nomad job validate examples/android-service.nomad.hcl
nomad job run examples/android-service.nomad.hcl
nomad job status android-service
```

查看 Allocation 状态：

```sh
nomad alloc status <allocation-id>
```

`android` 驱动只管理 APK 和 Android Service 生命周期。它使用宿主网络，不提供文件系统隔离、任务日志采集、`nomad alloc exec` 或 Nomad Service Registration。

## 10. 运行 Termux Shell 任务

示例文件：[`examples/termux-shell.nomad.hcl`](examples/termux-shell.nomad.hcl)

先在 Termux 中确认任务所需命令存在：

```sh
command -v sh
command -v uname
```

提交示例任务：

```sh
nomad job validate examples/termux-shell.nomad.hcl
nomad job run examples/termux-shell.nomad.hcl
nomad job status termux-shell
```

任务结束后查看输出：

```sh
nomad alloc logs <allocation-id> command
```

配置字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `command` | 是 | 可执行文件名或路径；开头的 `$PREFIX` 和 `~` 会展开为 Termux 路径 |
| `args` | 否 | 原样传给命令的参数列表 |
| `work_dir` | 否 | Termux 工作目录，默认使用 Termux Home |
| `stdin` | 否 | 传给命令标准输入的字符串 |

Nomad 任务的 `env` 会在执行前导出。`command`、`args`、环境变量值和工作目录会作为位置参数传给固定的生命周期包装器，不会拼接进包装器脚本。

`termux` 驱动有以下运行边界：

- 命令以 Termux 应用 UID 运行，不会切换为 Android 其他 UID；
- 使用宿主网络，不提供文件系统隔离、`nomad alloc exec` 或 Signal RPC；
- Termux 无法读取 Nomad Droid 的私有 Allocation 目录，工作目录应使用 Termux Home 或 Termux 可访问的存储路径；
- stdout 和 stderr 在命令结束后交给 Nomad 日志监视器，不会实时流式传输；
- Termux 的公开接口会将 stdout 与 stderr 的合计结果截断为 100 KB；发生截断时，Nomad Droid 会保留原始长度信息，并在 stderr 中追加截断标记；
- 设备重启会终止 Termux 外部进程。恢复 Agent 时，仍处于活动状态的记录会先标记为失败，再由 Nomad 按 Job 的重启策略处理。

停止任务时，驱动先向包装进程组发送 `TERM`；如果进程未在 Nomad 提供的停止超时内退出，再发送 `KILL`。

## 11. 停止任务或 Agent

停止 Nomad Job：

```sh
nomad job stop <job-name>
```

停止手机上的 Nomad Client：

1. 打开 Nomad Droid。
2. 点击 **Stop**。
3. 确认状态变为 `Stopped`，前台服务通知消失，并且 **Keep alive** 显示 `restore=off`。

## 12. 故障排查

### 节点没有出现在 `nomad node status`

1. 检查输入的是 Nomad RPC 地址，不是 HTTP API 地址。
2. 确认手机能够访问该地址的 TCP `4647` 端口。
3. 检查 **Last result** 中的连接或认证错误。
4. 如果集群要求客户端引入认证，检查 Client Introduction Token。
5. 点击 **Stop** 后重新启动 Agent。

### `android` 驱动不健康

使用 Shizuku 时：

1. 确认 Shizuku 应用显示服务正在运行。
2. 确认 Nomad Droid 状态包含 `permission=granted`。
3. 点击 **Connect broker**。
4. 确认状态包含 `broker=connected`。
5. 如果设备刚重启，先恢复 Shizuku，再等待 Nomad Droid 重新绑定。

使用 root 时：

1. 确认设备中存在可执行的 `su`。
2. 点击 **Check / grant root access** 并允许授权。
3. 确认 Root 状态包含 `uid=0` 和 `permission=granted`。
4. 确认 Job 的 `config` 中设置了 `privilege = "root"`。
5. 使用 `nomad node status -verbose <node-id>` 确认 `driver.android.root = true`。

### `termux` 驱动不健康

1. 确认安装的 Termux 版本不低于 `0.109`。
2. 确认 `~/.termux/termux.properties` 中设置了 `allow-external-apps=true`。
3. 确认 Nomad Droid 已获得 `RUN_COMMAND` 权限。
4. 点击 **Test setup**。
5. 确认最终状态包含 `setup=ready`。

### Termux 任务启动失败

1. 在 Termux 中使用 `command -v <命令>` 检查可执行文件。
2. 使用绝对路径或 `$PREFIX/bin/<命令>`。
3. 将 `work_dir` 设置为 Termux 有权访问的路径。
4. 检查 Nomad Allocation 的事件和 **Last result**。

### 熄屏后节点离线或 Termux 任务中断

1. 确认 Nomad Droid 的 **Keep alive** 显示 `Doze exemption=granted`。
2. 在厂商后台管理中将 Nomad Droid 设为不受限制。
3. 使用 Termux 时，将 Termux 也设为不受限制。
4. 确认没有对两个应用执行“强行停止”。

### Termux 日志缺失或被截断

Termux 日志只在命令结束后提交。如果输出超过 Termux 接口允许的容量，stderr 中会出现：

```text
[nomad-droid] Termux truncated command output
```

## 13. 开发验证

执行完整的 Android 构建检查：

```sh
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease
```

执行 Go 单元测试：

```sh
cd native/nomadcore
CGO_ENABLED=0 go test ./...
```

APK 输出目录：

```text
app/build/outputs/apk/debug/
app/build/outputs/apk/release/
```

Release APK 默认未签名，分发前需要使用自己的签名配置签名。

## 14. 项目目录

- `app/`：Android UI、前台服务、加密 Token 存储、Shizuku Broker、直接 root 适配，以及 Termux 命令结果和状态桥接；
- `native/nomadcore/`：嵌入式 Nomad Client、`android` 和 `termux` 驱动、本机 UID 鉴权桥接及 JNI 导出；
- `native/build-android.sh`：使用固定 NDK 版本构建 ARM64 `c-shared` 库；
- `examples/`：可修改后提交到 Nomad Server 的 Job 示例。

## 15. 参考资料

- [Nomad Job Specification](https://developer.hashicorp.com/nomad/docs/job-specification)
- [Shizuku 使用说明](https://shizuku.rikka.app/guide/setup/)
- [Termux RUN_COMMAND 说明](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Termux F-Droid 下载页](https://f-droid.org/packages/com.termux/)
