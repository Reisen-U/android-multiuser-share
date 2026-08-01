# Android 多用户共享

一个运行在 Termux 中的小型本地中转站。Android 的两个用户都可通过浏览器上传、下载文件，以及编辑一段共享文字。

## 特性

- 文件上传与下载
- 共享文字便签
- HTTP Basic 登录保护
- 不依赖云端；数据保存在运行服务的 Termux 用户中
- 单文件默认上限 512 MB，可调整

## 一键安装（推荐）

在运行服务的那个 Android 用户的 **Termux** 中执行这一行：

```sh
curl -fsSL https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main/install.sh | bash
```

安装器会自动安装 Python 和依赖，询问登录用户名与密码，然后立即启动服务。以后只需执行：

```sh
~/.local/bin/multiuser-share
```

## 手动安装

在要运行服务器的 Android 用户的 Termux 内执行：

```sh
pkg update && pkg install python git
git clone https://github.com/你的用户名/android-multiuser-share.git
cd android-multiuser-share
pip install -r requirements.txt
```

设置登录密码并启动。请换成自己的强密码：

```sh
export SHARE_USERNAME=share
export SHARE_PASSWORD='请改成一个长密码'
python app.py
```

默认数据目录是 `~/multiuser-share`；可改为：

```sh
export SHARE_DATA_DIR="$HOME/storage/shared/MultiUserShare"
```

> 使用共享存储前先执行一次 `termux-setup-storage`。不要把数据目录提交进 Git。

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
- 文件重名会覆盖旧文件；发布前若需要，可扩展为版本管理或删除功能。

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
