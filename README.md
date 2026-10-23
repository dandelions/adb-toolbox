# ADBToolbox (ADB助手) - 现代化开源重构工程

## 1. 项目简介
ADBToolbox 是参考「甲壳虫ADB助手 (adbhelper)」功能设计的现代化 Android 客户端工具。
基于 **Kotlin + 纯 JVM/Android 原生通信协议 + Material 3** 全新重构，摆脱了原应用对加固抽取层（dex2c / OLLVM / 专有 native so 库）的依赖，具备全架构通用、代码自主可控、现代化 UI、高性能和易扩展的特点。

## 2. 核心功能与实现对照表

| 功能模块 | 甲壳虫ADB助手实现 | 本项目 ADBToolbox 现代实现 |
| :--- | :--- | :--- |
| **通信传输层** | 内置 32 位 ARM Linux adb 二进制 + so | **纯 Kotlin 实现 ADB 协议**（支持任意架构，包括 ARM64, x86_64） |
| **设备连接方式** | WiFi 调试 (IP:5555) + OTG USB | **WiFi TCP Socket** + **Android UsbManager USB Host OTG 直连** |
| **设备概览** | 读取 getprop、电池、分辨率、存储 | 批量解析 `getprop`, `dumpsys battery`, `wm size`, `wm density`, `df` |
| **应用管理** | 列出应用、提取 APK、卸载、禁用、清除数据、安装 APK | `pm list`, `pm install`, `pm uninstall`, `pm clear`, `pm disable/enable`, `monkey` 启动 |
| **文件管理** | 远程目录浏览、上传 (Push)、下载 (Pull)、删除 | **原生 ADB Sync 协议**（二进制流直接收发，无需额外依赖），支持批量上传下载 |
| **投屏与控制** | Scrcpy 服务端投屏 + 触控映射 | 内置 `scrcpy-server.jar`，支持**低延迟屏幕拉流**与**全屏幕手势触摸映射 (input tap/swipe/keyevent)** |
| **终端控制台** | 交互式 Shell | 交互式 ADB Shell 命令行，支持命令历史与常用快捷指令 (`top`, `getprop` 等) |
| **Fastboot 工具箱** | 内置 fastboot 二进制 | **纯 USB Bulk 传输 Fastboot 协议客户端**，支持 `getvar all`, `flashing unlock`, 分区刷写, 重启 |
| **实用工具箱** | 修改 DPI/分辨率、免Root激活 | 一键修改/重置屏幕 DPI，免Root一键激活 Shizuku、黑阈、冰箱等 |

## 3. 技术架构设计

- **`core/model/`**：数据模型（`DeviceInfo`, `AppItem`, `FileItem`, `ConnectedDevice`）。
- **`core/adb/`**：
  - `AdbMessage`：ADB 协议 24 字节包头与数据帧解析与封装。
  - `AdbCrypto`：标准 RSA 2048 位密钥对生成、认证 Token 签名与公钥分发。
  - `AdbConnection`：全双工 ADB 消息收发与多路复用流通道管理。
  - `AdbStream`：双向流抽象。
  - `AdbSync`：ADB 二进制文件同步协议（Push、Pull、List 目录）。
  - `AdbClient`：上层业务操作接口。
  - `TcpTransport` / `UsbTransport`：统一的传输层抽象，双模式无缝切换。
- **`core/usb/`**：
  - `UsbManagerHelper`：USB Host 模式设备发现、权限申请与端点选择。
- **`core/fastboot/`**：
  - `FastbootClient`：Fastboot 模式命令交互与应答协议解析。
- **`core/scrcpy/`**：
  - `ScrcpyManager`：投屏服务端注入、截帧与多点触控/按键反向控制。
- **`ui/`**：
  - `MainActivity`：主控面板与设备概览。
  - `ConnectActivity`：WiFi 与 USB OTG 双模式连接向导。
  - `AppManagerActivity`：应用管理、提取、卸载、安装。
  - `FileManagerActivity`：远程文件系统浏览器与双向传输。
  - `ScrcpyActivity`：屏幕镜像投屏与远程控制面板。
  - `TerminalActivity`：交互式 ADB 终端控制台。
  - `FastbootActivity`：Fastboot 刷机与 Bootloader 解锁工具箱。

## 4. 构建与安装指南

### 环境要求
- JDK 17 / 21
- Android SDK (API Level 34, Build-Tools 34.0.0)
- Gradle 8.7+

### 编译指令
```bash
cd adb-toolbox
./gradlew assembleDebug
```
产物输出路径：`adb-toolbox/app/build/outputs/apk/debug/app-debug.apk`。
已提供在根目录：`adb-toolbox-v1.0.0.apk`。
