# ADBToolbox (ADB助手) - 现代化开源重构工程

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-purple.svg)](https://kotlinlang.org)
[![Material%203](https://img.shields.io/badge/Design-Material%203-indigo.svg)](https://m3.material.io)

> **ADBToolbox** 是一款专为 Android 开发者与极客打造的现代化 Android 端 ADB 综合调试与投屏工具。
> 采用 **纯 Kotlin + 原生 JVM/Android 网络通信栈 + Material 3** 全新设计开发，完全摒弃了传统工具依赖专有 Native `.so` 动态库与加固抽取壳的局限，具备 **全架构通用 (ARM64 / ARM32 / x86_64)、代码 100% 自主可控、极速响应、优雅现代** 的特点。

---

## 📱 核心功能特性一览

### 1. 🖵 Scrcpy 原生极速高清投屏与远程控制
- **60fps 低延迟流式镜像**：内置自研定制版 `scrcpy-server`，支持硬件级 H.264 编码与 MediaCodec 极速硬解，延迟低至数十毫秒。
- **微秒级触控注入**：采用原生二进制输入注入协议，支持多指手势、精确滑动轨迹、惯性滚动与全套物理按键映射。
- **自由交互布局**：
  - 导航栏多形态切换：**底部横条**、**左侧垂直栏**、**右侧垂直栏**、**悬浮球极简模式**、**全沉浸全屏隐藏**；
  - 控制按钮覆盖：返回键、主页键、多任务（最近应用）、锁屏/熄屏、音量加减、屏幕旋转控制。
- **被控端物理熄屏控制**：支持通过 SurfaceControl 切断被控端硬件屏幕供电，维持后台推流持续运行，实现极致省电、低温不发烫。
- **流式/截图双模式**：硬件流式投屏为主，提供纯 ADB 兼容截图模式作为容灾兜底。
- **切后台自愈保护**：Activity 切后台或多任务切换时，Surface 重建自动无缝重新绑定，不中断推流与控制。

### 2. 🎧 音频同步转发与虚拟耳机防外放（独家黑科技）
- **双模高清音频流**：支持 Android 10+ 系统内录（Submix）与全版本麦克风拾音模式，Opus / PCM 高保真传输。
- **后台常驻音频守护**：前台服务托管播放生命周期，投屏界面切换到后台时音乐、通话、游戏声音持续稳定输出。
- **虚拟耳机物理静音 (Virtual Headset)**：
  - 支持主控机一键向被控机插入/拔出“虚拟有线耳机”；
  - 接入后自动切断被控端扬声器物理外放（彻底静音），音频独占定向传输至主控机耳机/外放，实现真正安静无扰的私密投屏体验。

### 3. 📋 悬浮球快捷交互与双向剪贴板传输（v1.0.3 新增）
- **随心拖动微型悬浮球**：
  - 采用紧凑型毛玻璃半透明设计，支持任意方向全屏幕自由拖动与边缘自动吸附；
  - 单击快速弹出控制面板，集成虚拟耳机切换、全屏切换、熄屏控制、投屏设置等核心操作。
- **双向剪贴板互传面板 (Material 3)**：
  - **主控机 ➔ 被控机**：
    - 一键读取主控机系统剪贴板，支持自由编辑多行文本；
    - 「写入被控机剪贴板」：直接写入被控机系统剪贴板；
    - 「写入并立即粘贴」：写入剪贴板的同时，在对端聚焦输入框中自动触发一键粘贴。
  - **被控机 ➔ 主控机**：
    - 打开面板自动异步获取被控机当前剪贴板内容；
    - 实时监听被控端剪贴板变化并自动刷新；
    - 提供一键复制到主控机系统剪贴板功能。
  - **双通道自适应容灾**：Scrcpy 原生控制流优先（完美支持长文本、Unicode、Emoji 及特殊符号），ADB Shell `cmd clipboard` 降级兜底。

### 4. 🔗 多模式设备连接与配对
- **WiFi 无线 TCP 调试**：支持指定 IP 和端口（默认 5555），记录历史连接设备，一键秒级重连。
- **Android 11+ 无线配对向导**：支持无线调试配对码（Pairing Code）与配对端口配对。
- **USB OTG 直连**：基于 Android 原生 `UsbManager` (USB Host 模式)，插上 OTG 转接线即可免 PC、免 Root 直连被控设备。
- **RSA 密钥认证**：纯 Kotlin 实现 2048 位 RSA 密钥生成与 ADB 握手认证体系，自动管理密钥对。

### 5. 📦 应用管理 (App Manager)
- **多维度视图**：一键切换「第三方应用」与「系统应用」，支持实时拼音/包名/应用名模糊搜索。
- **APK 提取与备份**：一键完整提取设备中已安装的应用 APK 并保存至主控机。
- **全生命周期管控**：免 Root 静默安装 APK、一键卸载、冻结 (Disable) / 解冻 (Enable)、清除应用数据与缓存、强制停止、快速拉起。
- **应用信息透视**：查看 Target SDK、版本号、UID、安装路径及签名摘要。

### 6. 📁 文件管理器 (File Manager)
- **二进制 Sync 协议**：基于原生 ADB 二进制文件传输流（Push/Pull 协议），直连传输无中间损耗。
- **文件系统全景**：支持对 `/sdcard` 及系统目录的高速浏览、多级目录递归遍历与属性查看。
- **双向文件传输**：支持大文件分片高速上传 (Push) 与下载 (Pull)，支持创建目录、重命名与递归删除。

### 7. 💻 终端控制台 (Terminal)
- **交互式 ADB Shell**：全功能交互式命令行，实时回显输出。
- **极客常用指令集**：预置 `top`、`getprop`、`pm list`、`df -h`、`logcat` 等一键快捷执行。
- **命令历史记录**：支持上下翻阅历史执行记录。

### 8. 🛠️ 实用工具箱与设备概览
- **全方位硬件信息**：SOC 型号、CPU 核心与架构、系统版本、Build ID、电池健康度/温度/电量、存储空间分布等。
- **免 Root 黑科技一键激活**：一键激活 Shizuku、黑阈、小黑屋、冰箱 (IceBox) 等应用服务。
- **屏幕参数调节**：动态调整屏幕分辨率 (Resolution) 与屏幕密度 (DPI)，支持一键重置恢复。

### 9. ⚡ Fastboot 刷机工具箱
- **原生 USB Bulk Fastboot 客户端**：纯 Kotlin 实现 Fastboot 握手与数据交互。
- **刷机与解锁操作**：读取 Bootloader 变量信息、执行 `flashing unlock`、分区镜像刷写、系统重启与 Recovery/Bootloader 模式切换。

---

## 🎨 视觉与应用图标设计 (v1.0.3)
- **全新 Material 3 极客风格自适应图标 (Adaptive Icon)**：
  - **Android DNA**：饱满灵动的 Android 官方翠绿经典天线（`#3DDC84` ➔ `#00C853`）；
  - **终端核心 `>_`**：深色控制台内嵌荧光青蓝命令行符号与绿色光标，彰显极致控制力；
  - **互联桥梁 (Debug Bridge)**：底座流线型数据互联光轨与连接节点，生动呼应 ADB 通信与投屏镜像传输；
  - **规范适配**：分层支持 Android 8.0+ 自适应图标裁切，支持 Android 13+ Monochrome 动态主题壁纸染色，并向下兼容全版本标准矢量图标。

---

## 🏗️ 架构与模块划分

```text
com.toolbox.adbhelper
├── App.kt                     # 全局 Application 初始化
├── core                       # 核心业务协议层 (纯 Kotlin/JVM，不依赖 Native so)
│   ├── adb                    # 原生 ADB 协议引擎
│   │   ├── AdbClient.kt       # 高级业务操作 API
│   │   ├── AdbConnection.kt   # 全双工多路复用信道
│   │   ├── AdbCrypto.kt       # 2048位 RSA 签名与认证
│   │   ├── AdbMessage.kt      # 二进制 24 字节数据帧封包与解包
│   │   ├── AdbStream.kt       # 独立流通道抽象
│   │   ├── AdbSync.kt         # ADB 文件同步传输二进制协议
│   │   └── TcpTransport.kt    # TCP Socket 传输管道
│   ├── scrcpy                 # 投屏与控制流管理
│   │   ├── ScrcpyManager.kt   # 投屏/触控/按键/剪贴板控制引擎
│   │   ├── AudioStreamPlayer  # Opus/PCM 音频流解码播放器
│   │   ├── AudioPlaybackService # 前台常驻音频服务
│   │   └── VirtualHeadsetManager # 虚拟耳机插拔管理
│   ├── fastboot               # Fastboot USB 协议实现
│   ├── usb                    # Android UsbManager OTG 直连
│   └── model                  # 实体模型与预设配置
└── ui                         # 表现层 (Material 3 + ViewBinding)
    ├── MainActivity.kt        # 主控仪表盘与功能入口
    ├── ConnectActivity.kt     # 无线 WiFi / USB OTG 连接向导
    ├── ScrcpyActivity.kt      # 投屏核心控制台与悬浮面板
    ├── AppManagerActivity.kt  # 应用管理器
    ├── FileManagerActivity.kt # 文件浏览器与传输
    └── TerminalActivity.kt    # 交互式 Shell 终端
```

---

## 🛠️ 构建与安装指南

### 环境要求
- **操作系统**：Windows / macOS / Linux
- **JDK**：JDK 17 或 JDK 21
- **Android SDK**：API Level 34，Build-Tools 34.0.0
- **Gradle**：8.7+ (AGP 8.3.2)

### 编译指令
```bash
# 切换到项目工程目录
cd adb-toolbox

# 编译生成 Debug APK
./gradlew assembleDebug
```
产物位置：`adb-toolbox/app/build/outputs/apk/debug/app-debug.apk`。
项目根目录同步提供：`adb-toolbox-v1.0.0.apk` 与 `adb-toolbox-v1.0.3.apk`。

---

## 📝 变更日志

### v1.0.3 (Latest)
- **新增**：悬浮球菜单新增「📋 双向剪贴板传输」功能面板，支持主控机与被控机文本双向实时同步与一键粘贴。
- **新增**：全新设计的 Material 3 科技极客风格自适应应用图标（Adaptive Icon + Monochrome 动态主题染色）。
- **优化**：投屏界面悬浮球尺寸优化并支持全屏幕手势自由拖动、贴边保护与位置记忆重置。
- **修复**：彻底修复投屏在后台返回或多任务切换时误切断音频流及画面重建黑屏问题。

### v1.0.2
- **新增**：虚拟耳机 (Virtual Headset) 插拔模拟功能，一键切断对端扬声器物理外放，投屏音频独占监听。
- **优化**：音频转发服务迁移至前台常驻服务，保障切后台不中断播放。

### v1.0.0
- **初始发布**：纯 Kotlin/JVM 实现原生 ADB 协议栈，支持 WiFi / USB OTG 连接、Scrcpy 投屏、应用管理、文件传输、终端 Shell 与 Fastboot 工具箱。
