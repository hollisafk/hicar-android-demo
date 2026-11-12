# HiCar 安卓接收端（无感重连 / Android 5.1 兼容）

基于上游项目的修改版。正式签名包见本仓库 [Releases](https://github.com/hollisafk/hicar-android-demo/releases)。

本项目为社区实验，非华为官方产品，未获 HUAWEI HiCar 官方认证。HiCar 等名称与商标归其权利人所有。

## 上游项目声明

- 上游：[huducat586/hicar-android-receiver](https://github.com/huducat586/hicar-android-receiver)，基线提交 `a93b36f`（tag `v0.11.1-preview`）。
- 本仓库保留上游 git 历史，应用层源码与适配代码按 MIT 许可证发布；第三方源码与静态链接依赖保留各自许可证，详见仓库内 `THIRD_PARTY_NOTICES.txt`。
- 上游的型号 `00048000`、包名 `local.hicarlab`、`UiTheme` 主题色值、无自定义应用图标与 logo **一律未改动**；启动入口、分辨率自适应、连接码页按约定保留功能版实现。

## 本仓库修改内容

**无感回连（重连部分）**

- 已配对手机存在时进入 **45 秒静默重连窗口**，窗口内不启动 FDEE 广播、不请求系统蓝牙可发现，避免手机侧「靠近发现」弹窗与输码流程。
- 车机作为 CCMP initiator 主动拨手机 FE35；服务名解析失败时枚举候选 UUID 兜底，发 CCMP start 帧（79 字节）、再发 business 请求（kind 2 / TLV 3）取回 `endpoint`，直拨手机 **FE36 业务通道 8**，交给手机发起 `AUTH_START_REQUEST (0x0011)` 免密 STS。
- 认证成功后忽略手机主动关闭 FE35 / FE36 及部分 TCP 的动作，避免误判断线导致整轮重启、打断投屏。
- 开关默认值：`ccmp_initiator` / `chan_sweep` / `beacon13` = `true`，`passive_business` = `false`；三个已证伪的实验路径（`quiet_mode`、`scan_trigger`、`gatt_direct`）默认关闭。

**Android 5.1（API 22）兼容**

- `minSdk` 由 26 降至 **22**；前台服务、运行时权限、音频焦点、通知渠道、`SurfaceTexture`、`AudioTrack`、通知与 immersive 等均加 `SDK_INT` 守卫或双分支。
- Wi-Fi P2P 固定 5 GHz 信道 149（5745 MHz）：API 29+ 用隔离类 `P2pGroupV29` 的 Builder，API 22–28 用反射固定信道；高版本符号不进入低版本类加载路径。
- `app/build.sh` 改为 `--min-api 22`，并清理旧 `classes` / `dex` 避免僵尸类。

**native 层自洽**

- `native-auth/` 回退上游基线，JNI 符号为 `Java_local_hicarlab_NativeAuth_*`；`NativeAuth` 的 5 个可选实验方法改为纯 Java 空实现，避免预编译库缺符号时抛 `UnsatisfiedLinkError`。两个运行时认证开关保持默认关闭。
- 仓库不含编译产物：`app/native-libs/lib/` 被 `.gitignore` 排除，请按 `docs/BUILD.md` 由 `native-auth/` 源码自行构建。

## 实测设备与结果

| | 设备 |
| --- | --- |
| 接收端（车机） | PDA S60 / `620522031034` / **Android 9（API 28）** / arm64 |
| 发送端（手机） | **HUAWEI Mate 30 Pro / HarmonyOS 4.2** |

2026-11-12 真机实测，手机侧目视确认**实测良好**：

- **首次连接自动完成**：`has_pin=true / auto_connect_state=-1` → HiChain 消息 1/2/3 全部返回 0 → 后端报告认证成功 → 记住配对手机 `34:7E:00:08:5A:8B`。无需人工删除车辆记录或输入连接码。
- **5 GHz 落驱动**：`wpa_supplicant P2P-GROUP-STARTED ... freq=5745`；FE35 控制通道 5 / FE36 业务通道 4；TCP 三阶段 + DMSDP AES-CTR + HiSight / UIBC 全通。
- **无感重连连续两轮通过**（冷重启车机应用、全程不操作手机）：45 秒窗口内 `FDEE 广播启动成功` **0 次**、`广播兜底` 0 次；CCMP start → 手机回 `82 81 05 / 84 8C <摘要> / 86 81 00` → business 协商回 `endpoint=8` → 直拨 FE36 通道 8 → `0x0011` 免密 STS（HiChain 17/18 返回 0）→ 认证成功 → 视频出画。第 2 轮静默启动后约 10 秒完成认证、15 秒出画（240 帧）。
- **零点击证据**：FE36 连上到收到 `0x0011` 仅 **264 ms / 204 ms**；两轮 `has_pin=true` 0 次、`errorCode=1` 0 次、整轮重启 0 次。首次会话视频解码输出 900 帧，DMSDP 心跳持续，无崩溃、无 JNI 错误。

**未覆盖的边界**：32 位车机、Android 5.1 真机、HarmonyOS 5/6 及长时间稳定性。结果仅代表上述具体设备与操作，请在停车状态测试，勿用于依赖持续可靠连接的驾驶场景。
