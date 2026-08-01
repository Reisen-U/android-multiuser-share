#!/data/data/com.termux/files/usr/bin/bash
# 首次从 Git 克隆项目后运行一次：创建本地配置和启动器。
set -eu

APP_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
STATE_DIR="$HOME/.local/share/android-multiuser-share"
BIN_DIR="$HOME/.local/bin"
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
. "$CONFIG_FILE"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
exec python "$APP_DIR/app.py"
EOF
chmod 700 "$BIN_DIR/multiuser-share"

echo "配置完成。以后更新：cd $APP_DIR && git pull"
exec "$BIN_DIR/multiuser-share"
