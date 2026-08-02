#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 中一键安装 Android 多用户共享服务。
set -eu

REPO_RAW="https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main"
APP_DIR="$HOME/.local/share/android-multiuser-share"
BIN_DIR="${PREFIX:-/data/data/com.termux/files/usr}/bin"
CONFIG_FILE="$APP_DIR/config.env"

command -v pkg >/dev/null 2>&1 || {
  echo "错误：此安装器只能在 Termux 中运行。"
  exit 1
}

echo "==> 安装 Python"
pkg update -y
# 不要在这里升级 curl：全新 Termux 的 curl 已用于下载本脚本，而升级它可能
# 引入与预装 OpenSSL/QUIC 库不匹配的版本。完整系统升级请单独在 Termux 中执行。
pkg install -y python

mkdir -p "$APP_DIR" "$BIN_DIR"
python -c 'from urllib.request import urlretrieve; import sys; urlretrieve(sys.argv[1], sys.argv[2])' \
  "$REPO_RAW/app.py" "$APP_DIR/app.py"

if [ -f "$CONFIG_FILE" ]; then
  echo "检测到已有安装：保留登录设置和共享数据。"
else
  read -r -p "启用用户名密码保护？[Y/n] " SHARE_AUTH_REPLY </dev/tty
  case "${SHARE_AUTH_REPLY:-Y}" in
    n|N|no|NO)
      SHARE_AUTH_ENABLED=0
      SHARE_USERNAME=""
      SHARE_PASSWORD=""
      echo "警告：公开访问模式下，同一 Wi-Fi 的任何设备都可查看、上传和删除内容。"
      ;;
    *)
      SHARE_AUTH_ENABLED=1
      read -r -p "登录用户名 [share]: " SHARE_USERNAME </dev/tty
      SHARE_USERNAME="${SHARE_USERNAME:-share}"
      while :; do
        printf "登录密码（至少 12 位）： "
        stty -echo </dev/tty
        read -r SHARE_PASSWORD </dev/tty
        stty echo </dev/tty
        printf "\n"
        [ "${#SHARE_PASSWORD}" -ge 12 ] && break
        echo "密码太短，请重试。"
      done
      ;;
  esac

  umask 077
  {
    printf 'export SHARE_USERNAME=%q\n' "$SHARE_USERNAME"
    printf 'export SHARE_PASSWORD=%q\n' "$SHARE_PASSWORD"
    printf 'export SHARE_AUTH_ENABLED=%q\n' "$SHARE_AUTH_ENABLED"
    printf 'export SHARE_DATA_DIR=%q\n' "$HOME/multiuser-share"
    printf 'export SHARE_PORT=%q\n' "8080"
  } > "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
fi

cat > "$BIN_DIR/multiuser-share" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -eu
APP_DIR="$HOME/.local/share/android-multiuser-share"
RAW_APP="https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main/app.py"
if [ "${1:-}" = "update" ]; then
  echo "==> 正在更新多用户共享"
  python -c 'from urllib.request import urlretrieve; import sys; urlretrieve(sys.argv[1], sys.argv[2])' "$RAW_APP" "$APP_DIR/app.py.new"
  mv "$APP_DIR/app.py.new" "$APP_DIR/app.py"
  echo "更新完成，正在启动服务。"
fi
# shellcheck disable=SC1090
. "$APP_DIR/config.env"
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock
fi
exec python "$APP_DIR/app.py"
EOF
chmod 700 "$BIN_DIR/multiuser-share"

echo
echo "安装完成。服务现在启动；停止请按 Ctrl+C。"
echo "以后启动：multiuser-share"
echo "以后更新：multiuser-share update"
echo "在另一个 Android 用户访问：http://手机局域网IP:8080"
exec "$BIN_DIR/multiuser-share"
