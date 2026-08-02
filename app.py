"""Android 多用户之间使用的本地文本、文件中转站（仅 Python 标准库）。"""

import base64
import hmac
import html
import json
import mimetypes
import os
import re
import secrets
import shlex
import zipfile
from datetime import datetime, timedelta, timezone
from email.parser import BytesParser
from email.policy import default
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

DATA_DIR = Path(os.getenv("SHARE_DATA_DIR", Path.home() / "multiuser-share"))
FILES_DIR = DATA_DIR / "files"
NOTES_FILE = DATA_DIR / "notes.json"
LEGACY_NOTE_FILE = DATA_DIR / "note.txt"
FILES_META_FILE = DATA_DIR / "files.json"
CONFIG_FILE = Path(os.getenv("SHARE_CONFIG_FILE", Path.home() / ".local/share/android-multiuser-share/config.env"))
USERNAME = os.getenv("SHARE_USERNAME", "share")
PASSWORD = os.getenv("SHARE_PASSWORD", "")
AUTH_ENABLED = os.getenv("SHARE_AUTH_ENABLED", "1").lower() not in {"0", "false", "no"}
HOST = os.getenv("SHARE_HOST", "0.0.0.0")
PORT = int(os.getenv("SHARE_PORT", "8080"))
MAX_UPLOAD_MB = int(os.getenv("SHARE_MAX_UPLOAD_MB", "512"))
MAX_UPLOAD_BYTES = MAX_UPLOAD_MB * 1024 * 1024
MAX_REQUEST_BYTES = int(os.getenv("SHARE_MAX_REQUEST_MB", str(MAX_UPLOAD_MB * 4))) * 1024 * 1024

if AUTH_ENABLED and not PASSWORD:
    raise RuntimeError("请先设置 SHARE_PASSWORD，拒绝以无密码模式启动。")

FILES_DIR.mkdir(parents=True, exist_ok=True)


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso_time(value: datetime | None = None) -> str:
    return (value or now()).isoformat()


def load_json(path: Path, fallback):
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return fallback


def save_json(path: Path, value) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def load_notes() -> list:
    notes = load_json(NOTES_FILE, [])
    # 兼容原型版：把唯一保存的一段文本迁移成第一条文本。
    if LEGACY_NOTE_FILE.exists():
        text = LEGACY_NOTE_FILE.read_text(encoding="utf-8").strip()
        if text:
            notes.append({"id": secrets.token_urlsafe(9), "text": text, "created_at": iso_time()})
        LEGACY_NOTE_FILE.replace(LEGACY_NOTE_FILE.with_suffix(".migrated"))
        save_json(NOTES_FILE, notes)
    return notes


def readable_size(size: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.1f} {unit}" if unit != "B" else f"{size} B"
        size /= 1024


def format_time(value: str | None) -> str:
    if not value:
        return "永久保存"
    try:
        return datetime.fromisoformat(value).astimezone().strftime("%Y-%m-%d %H:%M") + " 过期"
    except ValueError:
        return "即将过期"


def format_created(value: str | None) -> str:
    try:
        return datetime.fromisoformat(value or "").astimezone().strftime("%m-%d %H:%M")
    except ValueError:
        return "刚刚"


def unique_filename(name: str) -> str:
    name = Path(name).name
    name = re.sub(r"[^\w. ()+\-\[\]{}@]", "_", name, flags=re.UNICODE).strip(".")
    if not name:
        return "upload"
    candidate = Path(name)
    index = 2
    while (FILES_DIR / candidate).exists():
        candidate = Path(f"{Path(name).stem} ({index}){Path(name).suffix}")
        index += 1
    return candidate.name


def clean_expired_files() -> dict:
    metadata = load_json(FILES_META_FILE, {})
    changed = False
    current = now()
    for name, details in list(metadata.items()):
        expiry = details.get("expires_at")
        expired = False
        if expiry:
            try:
                expired = datetime.fromisoformat(expiry) <= current
            except ValueError:
                expired = True
        file = FILES_DIR / Path(name).name
        if expired or not file.is_file():
            file.unlink(missing_ok=True)
            metadata.pop(name, None)
            changed = True
    if changed:
        save_json(FILES_META_FILE, metadata)
    return metadata


def is_image(name: str) -> bool:
    return (mimetypes.guess_type(name)[0] or "").startswith("image/")


def file_type_label(name: str) -> str:
    mime = mimetypes.guess_type(name)[0] or ""
    suffix = Path(name).suffix.removeprefix(".").upper()
    if mime.startswith("image/"):
        return "图片"
    if mime.startswith("video/"):
        return "视频"
    if mime.startswith("audio/"):
        return "音频"
    if suffix in {"PDF", "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX", "TXT", "MD"}:
        return f"{suffix} 文件"
    return f"{suffix} 文件" if suffix else "文件"


class ShareHandler(BaseHTTPRequestHandler):
    server_version = "AndroidMultiuserShare/2.0"

    def authenticated(self) -> bool:
        if not AUTH_ENABLED:
            return True
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header[6:]).decode("utf-8")
            username, password = decoded.split(":", 1)
        except (ValueError, UnicodeDecodeError):
            return False
        return hmac.compare_digest(username, USERNAME) and hmac.compare_digest(password, PASSWORD)

    def require_auth(self) -> bool:
        if self.authenticated():
            return True
        self.send_response(HTTPStatus.UNAUTHORIZED)
        self.send_header("WWW-Authenticate", 'Basic realm="Multiuser Share"')
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write("需要登录。".encode())
        return False

    def respond_html(self, content: str, status: HTTPStatus = HTTPStatus.OK) -> None:
        payload = content.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def redirect_home(self) -> None:
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", "/")
        self.end_headers()

    def do_GET(self) -> None:
        if not self.require_auth():
            return
        clean_expired_files()
        path = urlparse(self.path).path
        if path == "/":
            self.show_index()
        elif path.startswith("/download/"):
            self.serve_file(unquote(path.removeprefix("/download/")), attachment=True)
        elif path.startswith("/preview/"):
            self.serve_file(unquote(path.removeprefix("/preview/")), attachment=False)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        if not self.require_auth():
            return
        clean_expired_files()
        path = urlparse(self.path).path
        if path == "/notes":
            self.add_note()
        elif path == "/notes/delete-selected":
            self.delete_selected_notes()
        elif path == "/upload":
            self.upload()
        elif path == "/files/delete-selected":
            self.delete_selected_files()
        elif path == "/files/download-zip":
            self.download_zip()
        elif path == "/password":
            self.change_password()
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def show_index(self) -> None:
        notes = load_notes()
        notes.sort(key=lambda note: note.get("created_at", ""), reverse=True)
        note_cards = []
        for note in notes:
            text = str(note.get("text", ""))
            lines = text.splitlines() or [text]
            title = lines[0].strip() or "无标题文本"
            summary = "\n".join(lines[1:]).strip() or title
            note_cards.append(
                f'<article class="note"><span class="note-full" hidden>{html.escape(text)}</span><div class="note-head"><strong class="note-title">{html.escape(title)}</strong><button type="button" class="copy-note">复制</button></div><div class="note-text note-summary">{html.escape(summary)}</div><div class="note-footer"><span>{len(text)} 字 · {html.escape(format_created(note.get("created_at")))}</span><button type="button" class="quiet expand-note">展开全文</button></div><label class="note-select"><input type="checkbox" name="notes" value="{html.escape(note.get("id", ""), quote=True)}"> 选择</label></article>'
            )
        notes_html = "".join(note_cards) or "<p class=empty>暂无文本</p>"

        metadata = clean_expired_files()
        files_html = []
        files = [p for p in FILES_DIR.iterdir() if p.is_file()]
        files.sort(key=lambda p: metadata.get(p.name, {}).get("uploaded_at", datetime.fromtimestamp(p.stat().st_mtime, timezone.utc).isoformat()), reverse=True)
        for file in files:
            name = html.escape(file.name)
            link = quote(file.name)
            preview = f'<a class="file-visual" href="/preview/{link}" target="_blank"><img loading="lazy" src="/preview/{link}" alt="{name}"></a>' if is_image(file.name) else '<div class="file-visual file-icon">文件</div>'
            details = metadata.get(file.name, {})
            uploaded_at = details.get("uploaded_at", datetime.fromtimestamp(file.stat().st_mtime, timezone.utc).isoformat())
            expiry = format_time(details.get("expires_at"))
            files_html.append(f'<article class="file"><label class="select" title="选择文件"><input type="checkbox" name="files" value="{html.escape(file.name, quote=True)}" aria-label="选择 {name}"></label>{preview}<div class="file-info"><a class="file-name" href="/download/{link}">{name}</a><small class="file-expiry">{html.escape(expiry)}</small></div><time class="file-date">{html.escape(format_created(uploaded_at))}</time><span class="file-kind">{html.escape(file_type_label(file.name))}</span><span class="file-size">{readable_size(file.stat().st_size)}</span></article>')
        file_list = "\n".join(files_html) or "<p class=empty>暂无文件</p>"

        password_controls = '<details><summary>修改登录密码</summary><form action="/password" method="post"><div class="password-row"><input name="new_password" type="password" minlength="12" placeholder="新密码（至少 12 位）" required><input name="confirm_password" type="password" minlength="12" placeholder="再次输入新密码" required></div><button>更新密码</button><span class=hint>修改后，浏览器会要求使用新密码重新登录。</span></form></details>' if AUTH_ENABLED else '<p class="hint">当前为公开访问模式：同一 Wi-Fi 中的任何设备都可访问。</p>'
        self.respond_html(f"""<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>多用户共享</title>
<style>
body{{max-width:760px;margin:24px auto;padding:0 16px;font:16px system-ui;color:#1e1e1e;background:#fafafa}}h1{{margin:12px 0 20px}}h2{{margin-top:28px}}textarea,input{{font:inherit;box-sizing:border-box}}textarea{{width:100%;min-height:120px;padding:10px;border:1px solid #bbb;border-radius:8px}}input[type=file]{{max-width:100%}}input[type=number],input[type=password]{{padding:8px;border:1px solid #bbb;border-radius:6px}}button{{padding:8px 14px;border:0;border-radius:7px;background:#2563eb;color:#fff;font:inherit;margin-top:8px}}button.quiet{{padding:2px 0;background:none;color:#666;font-size:13px}}button.delete{{background:#fff1f0;color:#b42318;border:1px solid #fecaca}}button.copy-note{{padding:7px 16px;background:#eff6ff;color:#1d4ed8;border:1px solid #bfdbfe;font-size:15px;font-weight:600;margin:0;flex:none}}.tabs{{display:flex;gap:8px;border-bottom:1px solid #ddd;margin:16px 0 20px}}.tab-button{{border-radius:8px 8px 0 0;background:none;color:#555;margin:0;padding:10px 18px}}.tab-button.active{{color:#1d4ed8;border-bottom:3px solid #2563eb;font-weight:700}}.tab-panel[hidden]{{display:none}}.hint,small{{display:block;color:#666;margin-top:6px}}.note{{position:relative;background:#fff;border:1px solid #e4e4e4;border-radius:9px;padding:12px 74px 12px 12px;margin:10px 0;overflow-wrap:anywhere}}.note-head{{display:flex;gap:10px;align-items:center}}.note-title{{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:17px}}.note-summary{{white-space:pre-wrap;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:3;overflow:hidden;line-height:1.45;margin-top:8px;color:#333}}.note.expanded .note-summary{{display:block}}.note-footer{{display:flex;gap:8px;align-items:center;margin-top:6px;color:#666;font-size:13px}}.note-footer .quiet{{margin:0}}.note-select{{position:absolute;right:12px;bottom:12px;font-size:13px;color:#555;white-space:nowrap}}.notes-toolbar{{display:flex;align-items:center;justify-content:flex-end;margin:6px 0}}.file-toolbar{{display:flex;justify-content:flex-end;gap:6px;margin:8px 0}}.layout-button{{background:#fff;color:#555;border:1px solid #ddd;margin:0;padding:6px 10px;font-size:14px}}.layout-button.active{{background:#eff6ff;color:#1d4ed8;border-color:#93c5fd}}.files{{display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:12px}}.file-header{{display:none}}.file{{position:relative;background:#fff;border:1px solid #e4e4e4;border-radius:9px;padding:8px;min-width:0}}.file-visual{{display:grid;place-items:center;width:100%;height:112px;border-radius:6px;background:#eee;color:#666;overflow:hidden}}.file-visual img{{width:100%;height:100%;object-fit:cover}}.file-info{{min-width:0;padding-top:8px;overflow-wrap:anywhere}}.file-name{{color:#174ea6;font-weight:600;text-decoration:none;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}.file-expiry{{font-size:12px}}.file-date,.file-kind,.file-size{{display:none}}.select{{font-size:12px;color:#555;position:absolute;right:8px;top:8px;z-index:1;background:#ffffffdd;padding:3px;border-radius:4px;white-space:nowrap}}.files.list-layout{{display:block;border:1px solid #e4e4e4;border-radius:8px;background:#fff;overflow:hidden}}.files.list-layout .file-header,.files.list-layout .file{{display:grid;grid-template-columns:minmax(190px,2fr) minmax(100px,1fr) minmax(80px,1fr) 70px;gap:12px;align-items:center;padding:8px 12px}}.files.list-layout .file-header{{font-size:13px;color:#666;background:#f6f8fa;border-bottom:1px solid #e4e4e4}}.files.list-layout .file{{border:0;border-radius:0;border-bottom:1px solid #eee;min-height:38px}}.files.list-layout .file:last-child{{border-bottom:0}}.files.list-layout .file-visual{{display:none}}.files.list-layout .file-info{{padding:0;padding-left:28px;position:relative}}.files.list-layout .select{{left:0;right:auto;top:0;background:none;padding:0}}.files.list-layout .file-expiry{{display:none}}.files.list-layout .file-date,.files.list-layout .file-kind,.files.list-layout .file-size{{display:block;font-size:13px;color:#555}}.empty{{color:#777}}details{{background:#fff;border:1px solid #e4e4e4;border-radius:9px;padding:10px}}.password-row{{display:flex;gap:8px;align-items:center;flex-wrap:wrap}}@media (max-width:480px){{body{{margin-top:12px}}.files.list-layout .file-header{{display:none}}.files.list-layout .file{{grid-template-columns:minmax(0,1fr) 72px;padding:8px}}.files.list-layout .file-date,.files.list-layout .file-kind{{display:none}}.files.list-layout .file-size{{text-align:right}}}}
.files.list-layout .thumb-column{{display:none}}.files.list-layout.list-thumbnails .file-header,.files.list-layout.list-thumbnails .file{{grid-template-columns:52px minmax(160px,2fr) minmax(100px,1fr) minmax(80px,1fr) 70px}}.files.list-layout.list-thumbnails .thumb-column{{display:block}}.files.list-layout.list-thumbnails .file-visual{{display:grid;width:46px;height:46px}}.files.list-layout.list-thumbnails .file-info{{padding-left:28px}}@media (max-width:480px){{.files.list-layout.list-thumbnails .file{{grid-template-columns:52px minmax(0,1fr) 72px}}}}
/* 文件卡片：网格中上下分区；列表中按选择、缩略图、名称、大小排列。 */
.file{{display:grid;grid-template-columns:60px minmax(0,1fr);grid-template-rows:150px auto;padding:0;overflow:hidden;border:2px solid #202020;border-radius:7px;box-shadow:0 3px 0 #202020}}.file-visual{{grid-column:1 / -1;grid-row:1;width:100%;height:150px;border-radius:0;background:#628fe0}}.file-icon{{font-size:27px;color:#fff}}.file-info{{grid-column:2;grid-row:2;padding:10px 10px 9px 4px;align-self:center;overflow:hidden}}.file-name{{white-space:normal;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;line-height:1.35;overflow:hidden;overflow-wrap:anywhere}}.file-expiry{{font-size:11px}}.select{{position:static;grid-column:1;grid-row:2;align-self:end;justify-self:center;background:none;padding:10px 4px;font-size:0}}.select input{{width:18px;height:18px;margin:0}}.file-size{{display:none}}
.files.list-layout .file-header,.files.list-layout .file{{display:grid;grid-template-columns:46px minmax(0,1fr)70px;gap:10px;align-items:center;padding:8px 10px}}.files.list-layout .file{{min-height:46px;box-shadow:none;border:0;border-radius:0;border-bottom:1px solid #eee;overflow:visible}}.files.list-layout .file:last-child{{border-bottom:0}}.files.list-layout .file-visual{{display:none}}.files.list-layout .file-info{{grid-column:2;grid-row:1;padding:0;align-self:center}}.files.list-layout .file-name{{display:block;white-space:nowrap;text-overflow:ellipsis}}.files.list-layout .file-expiry{{display:none}}.files.list-layout .select{{grid-column:1;grid-row:1;padding:0;align-self:center}}.files.list-layout .file-date,.files.list-layout .file-kind{{display:none}}.files.list-layout .file-size{{display:block;grid-column:3;grid-row:1;font-size:13px;color:#555;text-align:right}}.files.list-layout.list-thumbnails .file-header,.files.list-layout.list-thumbnails .file{{grid-template-columns:46px 52px minmax(0,1fr)70px}}.files.list-layout.list-thumbnails .thumb-column{{display:block}}.files.list-layout.list-thumbnails .file-visual{{display:grid;grid-column:2;grid-row:1;width:46px;height:46px;border-radius:4px}}.files.list-layout.list-thumbnails .file-info{{grid-column:3}}.files.list-layout.list-thumbnails .file-size{{grid-column:4}}
@media (max-width:480px){{.file{{grid-template-columns:52px minmax(0,1fr);grid-template-rows:128px auto}}.file-visual{{height:128px}}.files.list-layout .file-header{{display:none}}.files.list-layout .file{{grid-template-columns:38px minmax(0,1fr)56px;padding:8px 6px;gap:6px}}.files.list-layout .file-size{{font-size:12px}}.files.list-layout.list-thumbnails .file{{grid-template-columns:38px 44px minmax(0,1fr)56px}}.files.list-layout.list-thumbnails .file-visual{{width:40px;height:40px}}}}
</style>
<h1>多用户共享</h1>
{password_controls}
<nav class="tabs"><button type="button" class="tab-button active" data-tab="notes">文本</button><button type="button" class="tab-button" data-tab="files">文件</button></nav>
<section class="tab-panel" id="notes-panel"><h2>共享文本</h2><form action="/notes" method="post"><textarea name="text" placeholder="输入一段共享文本" required></textarea><button>保存文本</button></form><form action="/notes/delete-selected" method="post"><div class="notes-toolbar"><button class="delete" onclick="return confirm(\'确认永久删除所有已选文本？\')">删除所选文本</button></div>{notes_html}</form></section>
<section class="tab-panel" id="files-panel" hidden><h2>上传文件</h2><form action="/upload" method="post" enctype="multipart/form-data"><input name="file" type="file" multiple required><div class=hint><label>过期时间（分钟，可留空永久保存）：<input name="expires_minutes" type="number" min="1" step="1" placeholder="例如 30"></label></div><button>上传所选文件</button></form><p class=hint>可一次选择多个文件；单个文件最大 {MAX_UPLOAD_MB} MB。过期文件会在下次访问时自动删除。</p><h2>文件</h2><form action="/files/download-zip" method="post"><div class="file-toolbar"><button type="button" class="layout-button active" data-layout="grid">网格</button><button type="button" class="layout-button" data-layout="list">列表</button><button type="button" class="layout-button" id="list-thumbnails" hidden>缩略图：关</button></div><p class=hint>勾选文件后可逐个下载到当前用户的 Download；ZIP 是兼容性备用方案。</p><p><button type="button" id="download-selected">逐个下载所选文件</button> <button>下载 ZIP（备用）</button> <button class="delete" formaction="/files/delete-selected" formmethod="post" onclick="return confirm(\'确认永久删除所有已选文件？\')">删除所选文件</button></p><div id="file-list" class="files"><div class="file-header"><span>选择</span><span class="thumb-column">缩略图</span><span>名称</span><span>大小</span></div>{file_list}</div></form></section><script>
document.getElementById('download-selected').addEventListener('click', () => {{
  const chosen = [...document.querySelectorAll('input[name="files"]:checked')];
  if (!chosen.length) {{ alert('请先选择文件。'); return; }}
  chosen.forEach((input, index) => setTimeout(() => {{
    const link = document.createElement('a');
    link.href = '/download/' + encodeURIComponent(input.value);
    link.download = input.value;
    document.body.appendChild(link); link.click(); link.remove();
  }}, index * 350));
}});
document.querySelectorAll('.copy-note').forEach(button => button.addEventListener('click', async () => {{
  const text = button.closest('.note').querySelector('.note-full').textContent;
  try {{
    await navigator.clipboard.writeText(text);
  }} catch (_) {{
    const helper = document.createElement('textarea');
    helper.value = text; document.body.appendChild(helper); helper.select();
    document.execCommand('copy'); helper.remove();
  }}
  const original = button.textContent; button.textContent = '已复制';
  setTimeout(() => {{ button.textContent = original; }}, 1200);
}}));
document.querySelectorAll('.expand-note').forEach(button => button.addEventListener('click', () => {{
  const note = button.closest('.note'); const expanded = note.classList.toggle('expanded');
  button.textContent = expanded ? '收起全文' : '展开全文';
}}));
const panels = {{notes: document.getElementById('notes-panel'), files: document.getElementById('files-panel')}};
function setTab(tab) {{
  Object.entries(panels).forEach(([name, panel]) => panel.hidden = name !== tab);
  document.querySelectorAll('.tab-button').forEach(button => button.classList.toggle('active', button.dataset.tab === tab));
  localStorage.setItem('multiuser-share-tab', tab);
}}
document.querySelectorAll('.tab-button').forEach(button => button.addEventListener('click', () => setTab(button.dataset.tab)));
setTab(localStorage.getItem('multiuser-share-tab') || 'notes');
const fileList = document.getElementById('file-list');
const thumbnailButton = document.getElementById('list-thumbnails');
function setLayout(layout) {{
  fileList.classList.toggle('list-layout', layout === 'list');
  document.querySelectorAll('.layout-button[data-layout]').forEach(button => button.classList.toggle('active', button.dataset.layout === layout));
  thumbnailButton.hidden = layout !== 'list';
  localStorage.setItem('multiuser-share-file-layout', layout);
}}
document.querySelectorAll('.layout-button[data-layout]').forEach(button => button.addEventListener('click', () => setLayout(button.dataset.layout)));
function setListThumbnails(enabled) {{
  fileList.classList.toggle('list-thumbnails', enabled);
  thumbnailButton.textContent = `缩略图：${{enabled ? '开' : '关'}}`;
  thumbnailButton.classList.toggle('active', enabled);
  localStorage.setItem('multiuser-share-list-thumbnails', enabled ? '1' : '0');
}}
thumbnailButton.addEventListener('click', () => setListThumbnails(!fileList.classList.contains('list-thumbnails')));
setLayout(localStorage.getItem('multiuser-share-file-layout') || 'grid');
setListThumbnails(localStorage.getItem('multiuser-share-list-thumbnails') === '1');
</script></html>""")

    def content_length(self) -> int | None:
        try:
            return int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.send_error(HTTPStatus.BAD_REQUEST, "无效的 Content-Length")
            return None

    def read_form(self):
        length = self.content_length()
        if length is None:
            return None
        return parse_qs(self.rfile.read(length).decode("utf-8", errors="replace"))

    def add_note(self) -> None:
        form = self.read_form()
        if form is None:
            return
        text = form.get("text", [""])[0].strip()
        if text:
            notes = load_notes()
            notes.append({"id": secrets.token_urlsafe(9), "text": text, "created_at": iso_time()})
            save_json(NOTES_FILE, notes)
        self.redirect_home()

    def delete_selected_notes(self) -> None:
        form = self.read_form()
        if form is None:
            return
        note_ids = set(form.get("notes", []))
        if not note_ids:
            self.send_error(HTTPStatus.BAD_REQUEST, "请至少选择一条文字")
            return
        notes = [note for note in load_notes() if note.get("id") not in note_ids]
        save_json(NOTES_FILE, notes)
        self.redirect_home()

    def change_password(self) -> None:
        global PASSWORD
        if not AUTH_ENABLED:
            self.send_error(HTTPStatus.BAD_REQUEST, "公开访问模式下不能修改密码")
            return
        form = self.read_form()
        if form is None:
            return
        password = form.get("new_password", [""])[0]
        confirmation = form.get("confirm_password", [""])[0]
        if len(password) < 12 or not hmac.compare_digest(password, confirmation):
            self.send_error(HTTPStatus.BAD_REQUEST, "密码至少需要 12 位，且两次输入必须一致")
            return
        try:
            CONFIG_FILE.parent.mkdir(parents=True, exist_ok=True)
            config = "\n".join((
                f"export SHARE_USERNAME={shlex.quote(USERNAME)}",
                f"export SHARE_PASSWORD={shlex.quote(password)}",
                "export SHARE_AUTH_ENABLED=1",
                f"export SHARE_DATA_DIR={shlex.quote(str(DATA_DIR))}",
                f"export SHARE_PORT={shlex.quote(str(PORT))}",
                "",
            ))
            temporary = CONFIG_FILE.with_suffix(".tmp")
            temporary.write_text(config, encoding="utf-8")
            temporary.chmod(0o600)
            temporary.replace(CONFIG_FILE)
        except OSError as error:
            self.send_error(HTTPStatus.INTERNAL_SERVER_ERROR, f"无法保存密码：{error}")
            return
        PASSWORD = password
        self.redirect_home()

    def upload(self) -> None:
        length = self.content_length()
        if length is None:
            return
        if length > MAX_REQUEST_BYTES:
            self.send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "本次上传总大小超过服务限制")
            return
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.send_error(HTTPStatus.BAD_REQUEST, "需要 multipart/form-data")
            return
        message = BytesParser(policy=default).parsebytes(
            f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode() + self.rfile.read(length)
        )
        form_part = next((part for part in message.iter_parts() if part.get_param("name", header="content-disposition") == "expires_minutes"), None)
        try:
            minutes = int((form_part.get_content() if form_part else "").strip() or "0")
        except ValueError:
            self.send_error(HTTPStatus.BAD_REQUEST, "过期时间必须是正整数分钟")
            return
        if minutes < 0:
            self.send_error(HTTPStatus.BAD_REQUEST, "过期时间不能为负数")
            return
        expires_at = iso_time(now() + timedelta(minutes=minutes)) if minutes else None
        metadata = clean_expired_files()
        uploads = [part for part in message.iter_parts() if part.get_param("name", header="content-disposition") == "file" and part.get_filename()]
        if not uploads:
            self.send_error(HTTPStatus.BAD_REQUEST, "请选择文件")
            return
        for part in uploads:
            data = part.get_payload(decode=True) or b""
            if len(data) > MAX_UPLOAD_BYTES:
                self.send_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, f"单个文件最大 {MAX_UPLOAD_MB} MB")
                return
            name = unique_filename(part.get_filename())
            (FILES_DIR / name).write_bytes(data)
            metadata[name] = {"uploaded_at": iso_time(), "expires_at": expires_at}
        save_json(FILES_META_FILE, metadata)
        self.redirect_home()

    def delete_selected_files(self) -> None:
        form = self.read_form()
        if form is None:
            return
        names = list(dict.fromkeys(Path(name).name for name in form.get("files", []) if name))
        if not names:
            self.send_error(HTTPStatus.BAD_REQUEST, "请至少选择一个文件")
            return
        metadata = clean_expired_files()
        for name in names:
            (FILES_DIR / name).unlink(missing_ok=True)
            metadata.pop(name, None)
        save_json(FILES_META_FILE, metadata)
        self.redirect_home()

    def download_zip(self) -> None:
        form = self.read_form()
        if form is None:
            return
        names = list(dict.fromkeys(Path(name).name for name in form.get("files", []) if name))
        files = [(name, FILES_DIR / name) for name in names if (FILES_DIR / name).is_file()]
        if not files:
            self.send_error(HTTPStatus.BAD_REQUEST, "请至少选择一个文件")
            return
        archive = DATA_DIR / f".download-{secrets.token_hex(8)}.zip"
        try:
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as bundle:
                for name, file in files:
                    bundle.write(file, arcname=name)
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Disposition", "attachment; filename=multiuser-share.zip")
            self.send_header("Content-Length", str(archive.stat().st_size))
            self.end_headers()
            with archive.open("rb") as handle:
                while chunk := handle.read(1024 * 1024):
                    self.wfile.write(chunk)
        finally:
            archive.unlink(missing_ok=True)

    def serve_file(self, name: str, attachment: bool) -> None:
        file = FILES_DIR / Path(name).name
        if not file.is_file() or file.name != name:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        mime = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(file.stat().st_size))
        disposition = "attachment" if attachment else "inline"
        self.send_header("Content-Disposition", f"{disposition}; filename*=UTF-8''{quote(file.name)}")
        self.end_headers()
        with file.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                self.wfile.write(chunk)


if __name__ == "__main__":
    print(f"多用户共享服务已启动：http://{HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), ShareHandler).serve_forever()
