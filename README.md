# HiCar 安卓接收端（无感重连 / Android 5.1 兼容）

基于上游项目的修改版，适用于社区实验和功能验证。正式签名包可在本仓库 [Releases](https://github.com/hollisafk/hicar-android-demo/releases) 下载。

> 本项目为社区实验，非华为官方产品，未获得 HUAWEI HiCar 官方认证。HiCar 等名称与商标归其权利人所有。

## 版本概览

本仓库在上游 [huducat586/hicar-android-receiver](https://github.com/huducat586/hicar-android-receiver) 的基线提交 `a93b36f`（tag `v0.11.1-preview`）基础上做了兼容性和连接稳定性适配，重点包括：

- 无感回连：在已配对手机存在时进入 45 秒静默窗口，尽量避免 FDEE 广播、系统蓝牙可发现和额外交互。
- Android 5.1（API 22）兼容：`minSdk` 降至 22，并对前台服务、权限、音频、通知、`SurfaceTexture`、`AudioTrack` 等做了兼容分支。
- native 层自洽：回退上游 JNI 符号并保留 Java 空实现，减少预编译库缺失导致的崩溃风险。

## 快速下载与安装

1. 打开本仓库的 [Releases](https://github.com/hollisafk/hicar-android-demo/releases)。
2. 选择最新 APK，例如 `HiCarLab-0.11.1.apk`。
3. 下载后安装到目标车机。
4. 若需要自行构建，请参阅 `docs/BUILD.md`，并遵循 `native-auth/` 的本地编译说明。

## 兼容性状态说明

当前代码已完成以下兼容适配：

- `minSdk`：22
- 目标版本：35
- 适配范围：Android 5.1（API 22）~ Android 15（API 35）

但请注意：

- 本仓库已完成代码级兼容适配，并做了相应分支处理；
- 但“Android 5.1 真机验证”仍未覆盖，当前公开结论仅代表已验证的设备组合与测试场景。
- 不建议将本仓库视为正式官方支持版本，更多是实验性功能验证与回归测试用例。

## 上游项目声明

- 上游：[huducat586/hicar-android-receiver](https://github.com/huducat586/hicar-android-receiver)，基线提交 `a93b36f`（tag `v0.11.1-preview`）。
- 本仓库保留上游 git 历史；应用层源码与适配代码按 MIT 许可证发布。
- 第三方源码与静态链接依赖保留各自许可证，详见仓库内 `THIRD_PARTY_NOTICES.txt`。

## 本仓库修改内容

### 无感回连（重连部分）

- 已配对手机存在时进入 45 秒静默重连窗口，窗口内不启动 FDEE 广播，不请求系统蓝牙可发现，尽量避免手机侧“靠近发现”弹窗和输入 PIN/码页流程。
- 车机作为 CCMP initiator 主动拨手机 FE35；服务名解析失败时枚举候选 UUID 兜底，随后发送 CCMP start 帧与业务请求，取回 `endpoint=8`，再直拨 FE36 业务通道 8。
- 认证成功后忽略手机主动关闭 FE35 / FE36 及部分 TCP 的动作，避免误判线重启导致整轮回连中断。
- 默认实验配置：`ccmp_initiator` / `chan_sweep` / `beacon13` = `true`，`passive_business` = `false`。
- 启动入口、分辨率自适应、连接码页部分优化。

### Android 5.1（API 22）兼容

- `minSdk` 由 26 降至 22；前台服务、运行时权限、音频焦点、通知渠道、`SurfaceTexture`、`AudioTrack`、通知与 immersive 等均加 `SDK_INT` 守卫或双分支。
- Wi‑Fi P2P 固定 5 GHz 信道 149（5745 MHz）：API 29+ 使用隔离类 `P2pGroupV29` 的 Builder，API 22–28 使用反射固定信道，避免高版本符号进入低版本类加载路径。
- `app/build.sh` 已改为 `--min-api 22`，并清理旧 `classes` / `dex`，减少僵尸类干扰。

### native 层自洽

- 仓库不含编译产物：`app/native-libs/lib/` 被 `.gitignore` 排除，自行构建请参照 `docs/BUILD.md`。

## 实测设备与结果

已验证设备组合（示例）：

| 角色 | 设备 | 结果 |
| --- | --- | --- |
| 接收端 | 车机 / Android 9（API 28） / arm64 | 通过 |
| 发送端 | HUAWEI Mate 30 Pro / HarmonyOS 4.2 | 通过 |

实测结论：

- 首次连接自动完成；HiChain 1/2/3 全返回 0。
- 无感重连在连续两轮冷启动回归中通过：45 秒静默窗口内 FDEE 广播 0 次、广播兜底 0 次。
- 认证链路正常，`has_pin=false` 且 `0x0011` 免密 STS 可成功建立，`errorCode=1` 0 次。
- 5 GHz 组网与视频输出稳定，未观察到明显的 JNI 错误或应用崩溃。

## 未覆盖的边界

- 32 位车机
- Android 5.1 真机验证
- HarmonyOS 5 / 6 设备
- 长时间稳定性测试

以上结论仅代表特定设备、版本和操作步骤。测试时请遵守当地法规与安全规范，驾驶中勿进行高风险操作。

## 公开说明

- 这是一个实验性仓库，功能特性和稳定性均可能随上游适配或环境变化而调整。
- 如需复现构建，请遵循 `docs/BUILD.md` 与 `native-auth` 目录内说明。
- 如需提交问题或反馈，请附上设备型号、系统版本、日志与复现步骤。

## 相关链接

- [Releases](https://github.com/hollisafk/hicar-android-demo/releases)
- [BUILD.md](docs/BUILD.md)
- [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)
