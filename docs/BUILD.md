# 构建说明

## 环境

- macOS 或 Linux/WSL，JDK 17 及以上、Python 3.12 及以上。
- Android SDK Platform 35、Build Tools 35.0.0。
- 重建原生库还需要 Android NDK 28.2.13676358、CMake 3.18 及以上、make、Perl、curl。
- 设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`；原生构建另设 `ANDROID_NDK_ROOT`。路径使用自己的安装位置，不需要采用开发者的目录。

## 1. 原生库

仓库提供固定版本的 OpenHarmony DeviceAuth/HUKS/securec 所需源码子集及适配代码。密码库按锁定版本从公开上游下载，下载后检查 SHA-256。第三方头部和许可证保留。

```sh
python3 native-auth/fetch-deps.py
python3 native-auth/build-android.py
```

脚本会分别生成 ARM64 和 ARMv7 原生库，放入 `app/native-libs/lib/`。该目录及编译产物不加入 Git。可用 `HICAR_BUILD_JOBS` 调整并行编译数，默认 4。

原生构建脚本为 macOS/Linux 路径做了整理；具体环境能否一次构建成功以实际结果为准。源码编译或 ABI 对齐通过不代表设备认证、32 位车机运行已通过。

## 2. 应用 APK

```sh
bash app/build.sh
```

默认在 `.local-signing/` 生成仅供本地开发的密钥，产物为 `app/build/HiCarLab-0.11.0-development.apk`。这个密钥不能用于官方持续更新，也不能覆盖使用不同证书签名的旧安装。

正式版本使用独立保存的发布密钥：

```sh
export HICAR_KEYSTORE='/absolute/path/to/your/release.keystore'
export HICAR_KEY_ALIAS='your-alias'
# 在自己的终端安全设置 HICAR_STORE_PASSWORD 与 HICAR_KEY_PASSWORD，勿提交到仓库。
bash app/build.sh
```

设置发布密钥后输出不带 `-development` 的 APK。密码通过环境变量传给签名工具，不写入脚本或仓库。

请妥善离线备份发布密钥。正式更新必须使用相同包名与证书，并递增 Manifest 中的 `versionCode`。

## 3. 主机协议回归

```sh
python3 scripts/test-host.py
```

测试依赖 JSON-java 20240303，由脚本从 Maven Central 下载并检查 SHA-256。测试使用合成向量和本机回环 TCP，不访问手机，也不验证实际 Wi-Fi、蓝牙、音视频硬件或开机行为。

## 目录

- `app/src`：安卓 UI、接收服务和协议实现。
- `app/tests`：主机端协议测试。
- `native-auth/runtime`：公开认证库的兼容层和 JNI。
- `native-auth/sources`：固定版本上游所需源码子集，各自保留许可证。
- `native-auth/dependency-lock.json`：上游版本、提交与下载摘要。
- `app/assets/licenses`：随 APK 打包的第三方许可证。

本仓库不包含本地签名私钥、手机配对数据、HUKS 运行存储、设备日志、厂商 APK 或系统镜像。
