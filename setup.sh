#!/data/data/com.termux/files/usr/bin/bash
# 首次从 Git 克隆项目后运行一次：创建本地配置和启动器。
set -eu

APP_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
STATE_DIR="$HOME/.local/share/android-multiuser-share"
BIN_DIR="${PREFIX:-/data/data/com.termux/files/usr}/bin"
CONFIG_FILE="$STATE_DIR/config.env"

command -v python >/dev/null 2>&1 || {
  echo "未找到 Python，正在安装..."
  pkg install -y python
}

mkdir -p "$STATE_DIR" "$BIN_DIR"

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
  printf 'export SHARE_CONFIG_FILE=%q\n' "$CONFIG_FILE"
} > "$CONFIG_FILE"
chmod 600 "$CONFIG_FILE"

cat > "$BIN_DIR/multiuser-share" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
set -eu
RAW_APP="https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main/app.py"
GH_PROXY_PREFIX="https://v4.gh-proxy.org/"
if [ "\${1:-}" = "update" ]; then
  echo "==> 正在更新多用户共享"
  if ! python -c 'import sys, urllib.request; data = urllib.request.urlopen(sys.argv[1], timeout=30).read(); open(sys.argv[2], "wb").write(data)' "\${GH_PROXY_PREFIX}\${RAW_APP}" "$APP_DIR/app.py.new" 2>/dev/null; then
    echo "代理下载失败，改用 GitHub 原地址。"
    python -c 'import sys, urllib.request; data = urllib.request.urlopen(sys.argv[1], timeout=30).read(); open(sys.argv[2], "wb").write(data)' "\$RAW_APP" "$APP_DIR/app.py.new"
  fi
  mv "$APP_DIR/app.py.new" "$APP_DIR/app.py"
  echo "更新完成，正在启动服务。"
fi
. "$CONFIG_FILE"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
exec python "$APP_DIR/app.py"
EOF
chmod 700 "$BIN_DIR/multiuser-share"

echo "配置完成。以后启动：multiuser-share"
echo "以后更新：multiuser-share update"
exec "$BIN_DIR/multiuser-share"
