# Android Multiuser Share

一个运行在 Android 设备上的轻量级 HTTP 文件共享服务器，可通过同一局域网中的浏览器共享文本与文件。

> **Vibe Coding 声明**：这是一个 Vibe Coding 项目。项目的需求梳理、界面设计、代码实现、调试与迭代主要通过人与 AI 协作完成。

## 项目定位

本项目本质上是一个由 Android App 托管的 HTTP 文件共享服务器。启动服务后，其他设备可以通过浏览器访问手机显示的局域网地址，进行文本复制、文件上传与下载，无需在客户端安装额外应用。

它也为 **Android 多用户/多配置文件环境下的文件共享** 提供了一种简单方案。Android 不同用户或工作资料之间的应用数据相互隔离时，可以借助本项目提供的 HTTP 页面，通过设备网络地址在不同用户环境之间传递文件。

当前仓库的主工程是 Android 原生版本，位于 `app/` 目录。

## 功能

- Android 前台服务，可启动、停止并在通知中查看运行状态。
- 可配置服务端口、用户名和 Basic Authentication 密码保护。
- 显示局域网访问地址与二维码，方便其他设备快速连接。
- 文本保存、复制、展开、全选和批量删除。
- 多文件上传、逐个下载、ZIP 打包下载和批量删除。
- 按上传时间分组，可直接选择整次上传的文件。
- 图片缩略图、网格/列表视图及选择状态保持。
- 可选文件过期时间、过期清理和重名文件自动改名。
- 数据保存于 App 私有目录，密码使用 Android Keystore 加密存储。

## 使用方法

1. 在 App 中设置端口和访问凭据。
2. 启动共享服务。
3. 让需要传输文件的设备或 Android 用户环境连接到同一网络。
4. 在浏览器中打开 App 显示的 HTTP 地址，或扫描二维码访问。
5. 在网页的“文本”和“文件”页面中进行共享操作。

## 构建 Android 版本

环境要求：

- Android Studio
- JDK 17
- Android SDK 35

使用 Android Studio 打开项目并运行 `app` 配置，或通过命令行构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux/macOS：

```bash
./gradlew :app:assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 安全提示

本项目提供的是局域网 HTTP 服务。建议开启密码保护，仅在可信网络中使用，并避免传输高度敏感的数据。
