#!/data/data/com.termux/files/usr/bin/bash
# 在 Termux 中一键安装 Android 多用户共享服务。
set -eu

REPO_RAW="https://raw.githubusercontent.com/Reisen-U/android-multiuser-share/main"
APP_DIR="$HOME/.local/share/android-multiuser-share"
BIN_DIR="$HOME/.local/bin"
CONFIG_FILE="$APP_DIR/config.env"

command -v pkg >/dev/null 2>&1 || {
  echo "错误：此安装器只能在 Termux 中运行。"
  exit 1
}

echo "==> 安装 Python 与 curl"
pkg update -y
# 避免只升级 curl 的部分依赖，导致本地 TLS 动态库版本不匹配。
pkg upgrade -y
pkg install -y python curl

echo "==> 安装服务依赖"
# Termux 由包管理器维护 pip；升级 pip 会被 Termux 拒绝。
python -m pip install --disable-pip-version-check 'Flask>=3.0,<4.0'

mkdir -p "$APP_DIR" "$BIN_DIR"
curl -fsSL "$REPO_RAW/app.py" -o "$APP_DIR/app.py"

read -r -p "登录用户名 [share]: " SHARE_USERNAME
SHARE_USERNAME="${SHARE_USERNAME:-share}"
while :; do
  printf "登录密码（至少 12 位）： "
  stty -echo
  read -r SHARE_PASSWORD
  stty echo
  printf "\n"
  [ "${#SHARE_PASSWORD}" -ge 12 ] && break
  echo "密码太短，请重试。"
done

umask 077
{
  printf 'export SHARE_USERNAME=%q\n' "$SHARE_USERNAME"
  printf 'export SHARE_PASSWORD=%q\n' "$SHARE_PASSWORD"
  printf 'export SHARE_DATA_DIR=%q\n' "$HOME/multiuser-share"
  printf 'export SHARE_PORT=%q\n' "8080"
} > "$CONFIG_FILE"
chmod 600 "$CONFIG_FILE"

cat > "$BIN_DIR/multiuser-share" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -eu
APP_DIR="$HOME/.local/share/android-multiuser-share"
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
echo "以后使用：$BIN_DIR/multiuser-share"
echo "在另一个 Android 用户访问：http://手机局域网IP:8080"
exec "$BIN_DIR/multiuser-share"
