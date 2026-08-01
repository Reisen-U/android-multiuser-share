"""Android 多用户之间使用的本地文件与文字中转站。"""

import hmac
import os
from functools import wraps
from pathlib import Path

from flask import Flask, Response, abort, redirect, render_template_string, request, send_from_directory, url_for
from werkzeug.utils import secure_filename

DATA_DIR = Path(os.getenv("SHARE_DATA_DIR", Path.home() / "multiuser-share"))
FILES_DIR = DATA_DIR / "files"
NOTE_FILE = DATA_DIR / "note.txt"
USERNAME = os.getenv("SHARE_USERNAME", "share")
PASSWORD = os.getenv("SHARE_PASSWORD", "")
HOST = os.getenv("SHARE_HOST", "0.0.0.0")
PORT = int(os.getenv("SHARE_PORT", "8080"))
MAX_UPLOAD_MB = int(os.getenv("SHARE_MAX_UPLOAD_MB", "512"))

if not PASSWORD:
    raise RuntimeError("请先设置 SHARE_PASSWORD，拒绝以无密码模式启动。")

FILES_DIR.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_UPLOAD_MB * 1024 * 1024

PAGE = """<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>多用户共享</title>
<style>body{max-width:720px;margin:32px auto;padding:0 16px;font:16px system-ui}textarea{width:100%;min-height:150px;box-sizing:border-box}li{margin:.5em 0}button{margin-top:.5em}</style>
<h1>多用户共享</h1>
<h2>共享文字</h2>
<form action="{{ url_for('save_note') }}" method="post"><textarea name="note" placeholder="在这里输入共享文字">{{ note }}</textarea><br><button>保存文字</button></form>
<h2>上传文件</h2>
<form action="{{ url_for('upload') }}" method="post" enctype="multipart/form-data"><input name="file" type="file" required><br><button>上传</button></form>
<p>单个文件最大 {{ max_upload_mb }} MB。</p>
<h2>文件</h2>
<ul>{% for item in files %}<li><a href="{{ url_for('download', name=item.name) }}">{{ item.name }}</a>（{{ item.size }}）</li>{% else %}<li>暂无文件</li>{% endfor %}</ul>
"""


def readable_size(size: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.1f} {unit}" if unit != "B" else f"{size} B"
        size /= 1024


def authorized(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        auth = request.authorization
        valid = auth and hmac.compare_digest(auth.username or "", USERNAME) and hmac.compare_digest(auth.password or "", PASSWORD)
        if not valid:
            return Response("需要登录。", 401, {"WWW-Authenticate": 'Basic realm="Multiuser Share"'})
        return view(*args, **kwargs)
    return wrapped


@app.get("/")
@authorized
def index():
    files = [{"name": f.name, "size": readable_size(f.stat().st_size)} for f in FILES_DIR.iterdir() if f.is_file()]
    files.sort(key=lambda item: item["name"].casefold())
    note = NOTE_FILE.read_text(encoding="utf-8") if NOTE_FILE.exists() else ""
    return render_template_string(PAGE, files=files, note=note, max_upload_mb=MAX_UPLOAD_MB)


@app.post("/note")
@authorized
def save_note():
    NOTE_FILE.write_text(request.form.get("note", ""), encoding="utf-8")
    return redirect(url_for("index"))


@app.post("/upload")
@authorized
def upload():
    incoming = request.files.get("file")
    if not incoming or not incoming.filename:
        abort(400, "请选择文件。")
    filename = secure_filename(incoming.filename)
    if not filename:
        abort(400, "文件名无效。")
    incoming.save(FILES_DIR / filename)
    return redirect(url_for("index"))


@app.get("/download/<path:name>")
@authorized
def download(name):
    filename = secure_filename(name)
    if not filename or filename != name:
        abort(404)
    return send_from_directory(FILES_DIR, filename, as_attachment=True)


@app.errorhandler(413)
def too_large(_error):
    return f"文件超过 {MAX_UPLOAD_MB} MB 限制。", 413


if __name__ == "__main__":
    app.run(host=HOST, port=PORT)
