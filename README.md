# Nomad Droid

Nomad Droid is an Android Nomad client that runs as a user-started foreground service. It embeds the Nomad client in a Go shared library and provides two built-in task drivers:

- `android` delegates a small, fixed set of package and service operations to a Shizuku UserService running as `shell` (or `root` when that is how Shizuku was started);
- `termux` executes non-privileged commands through Termux's public `RUN_COMMAND` service, under the Termux app UID.

The Shizuku privilege boundary still does **not** expose an arbitrary shell. Its broker accepts only:

- verify and install an APK with `pm install`;
- inspect an installed package;
- inspect, start, and stop one declared Android service component;
- force-stop one validated package.

## Requirements

- Android 12 or newer on an ARM64 device;
- Shizuku installed, started, and authorized for Nomad Droid when using the `android` driver;
- Termux `>= 0.109` when using the `termux` driver and collecting command results;
- a reachable Nomad server RPC address such as `10.0.0.10:4647`;
- for building: JDK 17 or newer, Android SDK 36, NDK `28.2.13676358`, and Go 1.26.

The basic client currently expects a non-TLS Nomad RPC endpoint. When Nomad ACLs require client introduction, enter a valid client introduction token in the app.

## Build

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the Android SDK, then run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The Gradle build cross-compiles the Go library for Android ARM64 before packaging the APK. The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Install the Nomad Droid APK and open it.
2. For Android service workloads, install and start Shizuku, tap the Shizuku **Grant access** button, and connect the broker.
3. For shell workloads, install Termux, set `allow-external-apps=true` in `~/.termux/termux.properties`, grant Nomad Droid the **Run commands in Termux environment** permission, and tap **Test setup**. The `termux` driver remains unhealthy until this test succeeds.
4. In **Keep alive**, open **Battery settings** and grant the Doze exemption if this device must maintain Nomad heartbeats while idle. On devices with aggressive app policies, also exempt Termux through **Termux app settings**.
5. Enter the Nomad server RPC address, node name, and datacenter. Add a client introduction token only if the cluster requires one.
6. Tap **Start agent**. Keep the foreground notification enabled so Android can keep the client process visible to the operating system.

After the node registers, schedule jobs with either built-in driver. See [`examples/android-service.nomad.hcl`](examples/android-service.nomad.hcl) and [`examples/termux-shell.nomad.hcl`](examples/termux-shell.nomad.hcl).

## Android workload contract

The target APK must declare the configured service as exported because the Shizuku shell process starts it. The service must also satisfy Android's foreground-service contract: call `startForeground(...)` promptly after launch, declare the matching foreground-service permission/type, and keep its own ongoing notification while running.

The driver configuration fields are:

| Field | Required | Meaning |
| --- | --- | --- |
| `package` | yes | Android application ID, for example `com.example.worker` |
| `service` | yes | Service class, for example `.NomadWorkService` |
| `install` | no | Install the APK artifact before starting; defaults to `true` |
| `apk_path` | when installing | APK path inside the Nomad allocation directory |
| `sha256` | when installing | Expected lowercase SHA-256 digest |
| `replace` | no | Pass `-r` to `pm install`; defaults to `true` |

The base driver uses host networking, provides no process exec, filesystem isolation, task log collection, or Nomad service registration. It manages Android service lifecycle only.

## Termux shell contract

The `termux` driver uses the explicit `com.termux.app.RunCommandService`; it never forwards shell text to the Shizuku broker. The command is launched as a positional argument to a fixed lifecycle wrapper, so `command`, `args`, environment values, and working-directory values are not interpolated into that wrapper.

| Field | Required | Meaning |
| --- | --- | --- |
| `command` | yes | Executable name or path. `$PREFIX` and `~` prefixes are expanded to Termux paths. |
| `args` | no | Argument list passed unchanged to the command. |
| `work_dir` | no | Termux working directory; defaults to the Termux home directory. |
| `stdin` | no | String supplied to the command's standard input. |

Nomad task `env` values are exported before execution. The driver uses host networking and has no filesystem isolation, `nomad alloc exec`, signal RPC, or Android-UID switching. Termux cannot read Nomad Droid's private allocation directory, so commands should use Termux home or storage paths available to Termux.

Termux returns background-command stdout and stderr through a `PendingIntent`. Its public contract truncates their combined result to 100 KB; Nomad Droid preserves the reported original lengths and appends a truncation marker to stderr. Output is handed to Nomad's log monitor after the command completes, not streamed while it runs.

## Keep-alive behavior

- The agent is a `specialUse` foreground service with an ongoing notification and `START_STICKY` restart semantics.
- While the user-selected desired state is running, it holds a non-reference-counted partial wake lock so Nomad timers and heartbeats can execute with the screen off.
- The desired state is persisted. `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` restore the foreground service after reboot or APK update.
- **Stop** clears the desired state before shutting down, so boot/update restoration does not revive a user-stopped agent.
- The app exposes Android's battery-optimization exemption prompt because Doze would otherwise suspend continuous network activity. A force-stop or a vendor "restricted" battery policy still suppresses app restarts until the user launches the app again.
- A dead Shizuku UserService binding is cleared and rebound while Shizuku remains available. Shizuku must itself be running after a reboot; until its Binder returns, the Nomad node can reconnect but the `android` driver fingerprints as unhealthy.
- Termux task state and command results are persisted for Nomad task recovery. A device reboot necessarily kills those external processes, so active records are marked failed before the Nomad client restores them; Nomad can then apply the job's restart policy instead of treating a dead command as running.
- Termux runs in a separate Android app process. Nomad Droid's wake lock cannot exempt Termux from vendor process killing, which is why Termux's own battery policy must be configured on affected devices.

## Code layout

- `app/`: Android UI, foreground service, encrypted token store, Shizuku broker, and Termux `RUN_COMMAND` result/state bridge.
- `native/nomadcore/`: embedded Nomad client, Android and Termux drivers, local UID-authenticated bridge, and JNI exports.
- `native/build-android.sh`: reproducible ARM64 `c-shared` cross-build using the pinned NDK.
