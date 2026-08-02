# Android 多用户共享

一个运行在 Termux 中的小型本地中转站。Android 的多个用户都可通过浏览器共享文本和文件。

> 本项目是一个 Vibe Coding 项目。

## 特性

- 多文件上传、勾选后逐个下载或下载 ZIP
- 图片缩略图、网格/列表/列表缩略图三种文件视图
- 可设置文件在 N 分钟后过期，并支持批量删除
- 文本列表、摘要展开与一键复制
- 可选的用户名和密码保护
- 无需额外安装 Python 库
- 不依赖云端；数据保存在运行服务的 Termux 用户中
- 单文件默认上限 512 MB，可调整

## 一键安装

在运行服务的那个 Android 用户的 **Termux** 中执行这一行：

```sh
curl -fsSL https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main/install.sh | bash
```

安装过程会让你选择是否启用用户名和密码保护；完成后服务会自动启动。以后只需执行：

```sh
multiuser-share
```

## 日常启动

日后启动服务：

```sh
multiuser-share
```

## 更新

先按 `Ctrl+C` 停止正在运行的服务，再执行：

```sh
multiuser-share update
```

更新不会删除已保存的文本、文件或登录设置；完成后服务会自动重新启动。

若你安装的是较早版本、还不能使用上述命令，重新执行“一键安装”命令一次即可。安装器会保留已有的登录设置和共享数据。

## 访问

在 Termux 获取手机局域网 IP：

```sh
ip route get 1.1.1.1
```

找到输出里的 `src 192.168.x.x`。两个 Android 用户的浏览器都访问：

```text
http://192.168.x.x:8080
```

若启用了密码保护，输入安装时设置的用户名和密码。服务在一个 Android 用户的 Termux 中运行；其他用户只需用浏览器访问。

## 安全与稳定性

- 服务器会监听局域网，**同一 Wi-Fi 的设备也可以访问**；务必使用强密码，不要在不可信 Wi-Fi 使用。
- 这是 HTTP 而非 HTTPS，不适合传输高度敏感文件。
- Termux 后台可能被系统结束。可运行 `termux-wake-lock`，并在 Android 设置中关闭 Termux 的电池优化。
- 同名文件会自动加序号，避免覆盖原文件。

## 许可

MIT
