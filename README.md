# Android 多用户共享

一个运行在 Termux 中的小型本地中转站。Android 的多个用户都可通过浏览器共享备忘录和文件。

## 特性

- 多文件上传、勾选后逐个下载或下载 ZIP
- 图片缩略图、网格/列表/列表缩略图三种文件视图
- 可设置文件在 N 分钟后过期，并支持批量删除
- 备忘录列表、摘要展开与一键复制
- HTTP Basic 登录保护
- 仅使用 Python 标准库，无 Flask、pip 或编译依赖
- 不依赖云端；数据保存在运行服务的 Termux 用户中
- 单文件默认上限 512 MB，可调整

## 一键安装（可选）

在运行服务的那个 Android 用户的 **Termux** 中执行这一行：

```sh
curl -fsSL https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main/install.sh | bash
```

安装器只更新软件包索引并安装 Python；它**不会**进行完整系统升级，也不会在安装过程中升级用于启动安装器的 curl。询问登录用户名与密码后会立即启动服务。以后只需执行：

```sh
multiuser-share
```

## 日常启动

日后启动服务：

```sh
multiuser-share
```

## 访问

在 Termux 获取手机局域网 IP：

```sh
ip route get 1.1.1.1
```

找到输出里的 `src 192.168.x.x`。两个 Android 用户的浏览器都访问：

```text
http://192.168.x.x:8080
```

输入上一步设置的用户名和密码。服务在主用户里运行即可；另一个用户只需浏览器。

## 安全与稳定性

- 服务器会监听局域网，**同一 Wi-Fi 的设备也可能尝试访问**；务必使用强密码，不要在不可信 Wi-Fi 使用。
- 这是 HTTP 而非 HTTPS，不适合传输高度敏感文件。
- Termux 后台可能被系统结束。可运行 `termux-wake-lock`，并在 Android 设置中关闭 Termux 的电池优化。
- 同名文件会自动加序号，避免覆盖原文件。

## 配置项

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SHARE_USERNAME` | `share` | 登录名 |
| `SHARE_PASSWORD` | 无（必须设置） | 登录密码 |
| `SHARE_PORT` | `8080` | 监听端口 |
| `SHARE_MAX_UPLOAD_MB` | `512` | 单文件大小上限 |
| `SHARE_DATA_DIR` | `~/multiuser-share` | 数据保存位置 |

## 许可

MIT
