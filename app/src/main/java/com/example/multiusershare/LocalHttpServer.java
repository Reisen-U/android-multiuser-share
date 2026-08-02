package com.example.multiusershare;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free HTTP server used by the foreground service. */
final class LocalHttpServer {
    private static final String TAG = "LocalHttpServer";
    private static final int MAX_FILE_BYTES = 512 * 1024 * 1024;
    private static final String CRLF = "\r\n";
    private final Context context;
    private final String username;
    private final String password;
    private final boolean authEnabled;
    private final int port;
    private final File dataDir;
    private final File filesDir;
    private final File notesFile;
    private final File metadataFile;
    private final Object dataLock = new Object();
    private ServerSocket serverSocket;
    private volatile boolean running;
    private final ExecutorService workers = Executors.newCachedThreadPool();

    LocalHttpServer(Context context, String username, String password, boolean authEnabled, int port) {
        this.context = context.getApplicationContext();
        this.username = username;
        this.password = password;
        this.authEnabled = authEnabled;
        this.port = port;
        this.dataDir = new File(this.context.getFilesDir(), "shared");
        this.filesDir = new File(dataDir, "files");
        this.notesFile = new File(dataDir, "notes.json");
        this.metadataFile = new File(dataDir, "files.json");
    }

    void start() throws IOException {
        if (port < 1024 || port > 65535) throw new IOException("端口范围为 1024-65535");
        if (!dataDir.mkdirs() && !dataDir.isDirectory()) throw new IOException("无法创建数据目录");
        if (!filesDir.mkdirs() && !filesDir.isDirectory()) throw new IOException("无法创建文件目录");
        serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName("0.0.0.0"));
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    workers.execute(() -> handleSocket(socket));
                } catch (Exception e) {
                    if (running) Log.e(TAG, "等待浏览器连接失败", e);
                }
            }
        }, "share-acceptor");
        acceptor.start();
    }

    private void handleSocket(Socket socket) {
        try {
            socket.setSoTimeout(120_000);
            handle(socket);
        } catch (Exception e) {
            Log.e(TAG, "处理浏览器请求失败", e);
            try {
                if (!socket.isClosed()) sendText(new BufferedOutputStream(socket.getOutputStream()), 500, "操作失败，请稍后重试");
            } catch (IOException ignored) { }
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    void stop() {
        running = false;
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }

    private void handle(Socket socket) throws Exception {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] bits = requestLine.split(" ", 3);
        if (bits.length < 2) { sendText(output, 400, "请求格式无效"); return; }
        String method = bits[0].toUpperCase(Locale.ROOT);
        String target = bits[1];
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        if ("OPTIONS".equals(method)) { sendBytes(output, 204, "", "text/plain; charset=utf-8", false); return; }
        if (!authenticated(headers.get("authorization"))) { sendUnauthorized(output); return; }
        String path = target;
        int q = path.indexOf('?');
        String query = q >= 0 ? path.substring(q + 1) : "";
        path = q >= 0 ? path.substring(0, q) : path;
        path = URLDecoder.decode(path, "UTF-8");
        if ("GET".equals(method)) {
            if ("/".equals(path)) sendBytes(output, 200, SharePage.load(context), "text/html; charset=utf-8", false);
            else if ("/api/state".equals(path)) sendJson(output, stateJson());
            else if ("/download".equals(path) || "/preview".equals(path)) serveFile(output, params(query).get("name"), "/download".equals(path));
            else sendText(output, 404, "未找到");
            return;
        }
        if ("POST".equals(method)) {
            long length = parseLong(headers.get("content-length"), 0);
            if (length > MAX_FILE_BYTES + 8 * 1024 * 1024) { sendText(output, 413, "请求过大，单文件上限 512 MB"); return; }
            String type = headers.getOrDefault("content-type", "");
            if ("/api/notes".equals(path)) {
                JSONObject body = new JSONObject(new String(readBody(input, length), StandardCharsets.UTF_8));
                addNote(body.optString("text", ""));
                sendJson(output, new JSONObject().put("ok", true));
            } else if ("/api/notes/delete".equals(path)) {
                deleteNotes(new JSONObject(new String(readBody(input, length), StandardCharsets.UTF_8)).optJSONArray("ids"));
                sendJson(output, new JSONObject().put("ok", true));
            } else if ("/api/files/delete".equals(path)) {
                deleteFiles(new JSONObject(new String(readBody(input, length), StandardCharsets.UTF_8)).optJSONArray("names"));
                sendJson(output, new JSONObject().put("ok", true));
            } else if ("/api/files/zip".equals(path)) {
                JSONArray names = new JSONObject(new String(readBody(input, length), StandardCharsets.UTF_8)).optJSONArray("names");
                File zip = createZip(names);
                serveFileAndDelete(output, zip, "shared-files.zip", "application/zip");
            } else if ("/api/files/upload".equals(path) && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                handleUpload(output, input, length, type);
            } else {
                sendText(output, 404, "未找到");
            }
            return;
        }
        sendText(output, 405, "不支持的方法");
    }

    private boolean authenticated(String authorization) {
        if (!authEnabled) return true;
        if (authorization == null || !authorization.startsWith("Basic ")) return false;
        try {
            String value = new String(java.util.Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int split = value.indexOf(':');
            return split >= 0 && username.equals(value.substring(0, split)) && password.equals(value.substring(split + 1));
        } catch (Exception e) { return false; }
    }

    private void handleUpload(BufferedOutputStream output, InputStream input, long length, String contentType) throws Exception {
        byte[] body = readBody(input, length);
        Matcher matcher = Pattern.compile("boundary=\\\"?([^;\\\"]+)\\\"?", Pattern.CASE_INSENSITIVE).matcher(contentType);
        if (!matcher.find()) { sendText(output, 400, "上传格式无效"); return; }
        String boundary = matcher.group(1);
        byte[] marker = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        String batchId = UUID.randomUUID().toString();
        String batchCreatedAt = Instant.now().toString();
        int cursor = indexOf(body, marker, 0);
        int expiryMinutes = 0;
        int saved = 0;
        while (cursor >= 0 && cursor < body.length) {
            int partStart = cursor + marker.length;
            if (partStart + 2 <= body.length && body[partStart] == '-' && body[partStart + 1] == '-') break;
            if (partStart + 2 <= body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') partStart += 2;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), partStart);
            if (headerEnd < 0) break;
            String partHeaders = new String(body, partStart, headerEnd - partStart, StandardCharsets.ISO_8859_1);
            int next = indexOf(body, marker, headerEnd + 4);
            if (next < 0) break;
            int dataEnd = Math.max(headerEnd + 4, next - 2);
            Matcher disposition = Pattern.compile("name=\"([^\"]+)\"(?:;\\s*filename=\"([^\"]*)\")?", Pattern.CASE_INSENSITIVE).matcher(partHeaders);
            if (disposition.find()) {
                String name = disposition.group(1);
                String original = disposition.group(2);
                if (original == null) {
                    if ("expiry".equals(name)) {
                        try { expiryMinutes = Math.max(0, Integer.parseInt(new String(body, headerEnd + 4, dataEnd - headerEnd - 4, StandardCharsets.UTF_8).trim())); }
                        catch (Exception ignored) { expiryMinutes = 0; }
                    }
                } else if (!original.isEmpty() && dataEnd > headerEnd + 4) {
                    String safe = uniqueFilename(original);
                    File target = new File(filesDir, safe);
                    try (FileOutputStream file = new FileOutputStream(target)) {
                        file.write(body, headerEnd + 4, dataEnd - headerEnd - 4);
                    }
                    JSONObject metadata = readMetadata();
                    JSONObject item = new JSONObject().put("size", target.length()).put("mime", guessMime(safe))
                            .put("createdAt", batchCreatedAt).put("batchId", batchId).put("batchCreatedAt", batchCreatedAt);
                    if (expiryMinutes > 0) item.put("expiresAt", Instant.now().plusSeconds(expiryMinutes * 60L).toString());
                    metadata.put(safe, item);
                    saveMetadata(metadata);
                    saved++;
                }
            }
            cursor = next;
        }
        sendJson(output, new JSONObject().put("ok", true).put("saved", saved));
    }

    private JSONObject stateJson() {
        synchronized (dataLock) {
            try {
                cleanExpiredFiles();
                JSONArray notes = readNotes();
                List<JSONObject> noteList = new ArrayList<>();
                for (int i = 0; i < notes.length(); i++) noteList.add(notes.optJSONObject(i));
                Collections.sort(noteList, (a, b) -> b.optString("createdAt").compareTo(a.optString("createdAt")));
                JSONArray sortedNotes = new JSONArray();
                for (JSONObject note : noteList) sortedNotes.put(note);
                JSONObject meta = readMetadata();
                JSONArray files = new JSONArray();
                List<String> names = new ArrayList<>();
                java.util.Iterator<String> keys = meta.keys();
                while (keys.hasNext()) names.add(keys.next());
                Collections.sort(names, (a, b) -> meta.optJSONObject(b).optString("createdAt").compareTo(meta.optJSONObject(a).optString("createdAt")));
                for (String name : names) files.put(new JSONObject(meta.optJSONObject(name).toString()).put("name", name));
                return new JSONObject().put("notes", sortedNotes).put("files", files).put("auth", authEnabled).put("username", username);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private void addNote(String text) throws Exception {
        if (text.trim().isEmpty()) return;
        synchronized (dataLock) {
            JSONArray notes = readNotes();
            notes.put(new JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("createdAt", Instant.now().toString()));
            saveNotes(notes);
        }
    }

    private void deleteNotes(JSONArray ids) throws Exception {
        if (ids == null) return;
        synchronized (dataLock) {
            List<String> remove = new ArrayList<>();
            for (int i = 0; i < ids.length(); i++) remove.add(ids.optString(i));
            JSONArray notes = readNotes(); JSONArray kept = new JSONArray();
            for (int i = 0; i < notes.length(); i++) if (!remove.contains(notes.optJSONObject(i).optString("id"))) kept.put(notes.optJSONObject(i));
            saveNotes(kept);
        }
    }

    private void deleteFiles(JSONArray names) throws Exception {
        if (names == null) return;
        synchronized (dataLock) {
            JSONObject meta = readMetadata();
            for (int i = 0; i < names.length(); i++) {
                String name = safeName(names.optString(i));
                if (!name.isEmpty()) new File(filesDir, name).delete();
                meta.remove(name);
            }
            saveMetadata(meta);
        }
    }

    private File createZip(JSONArray names) throws Exception {
        File zip = new File(context.getCacheDir(), "shared-" + UUID.randomUUID() + ".zip");
        JSONObject meta = readMetadata();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String name = safeName(names.optString(i)); File file = new File(filesDir, name);
                if (!meta.has(name) || !file.isFile()) continue;
                out.putNextEntry(new ZipEntry(name));
                try (FileInputStream in = new FileInputStream(file)) { copy(in, out); }
                out.closeEntry();
            }
        }
        return zip;
    }

    private void serveFile(BufferedOutputStream output, String name, boolean attachment) throws IOException {
        cleanExpiredFiles();
        String safe = safeName(name); File file = new File(filesDir, safe);
        if (safe.isEmpty() || !file.isFile()) { sendText(output, 404, "文件不存在"); return; }
        serveFileAndDelete(output, file, safe, guessMime(safe), !attachment);
    }

    private void serveFileAndDelete(BufferedOutputStream output, File file, String name, String mime) throws IOException { serveFileAndDelete(output, file, name, mime, false); }
    private void serveFileAndDelete(BufferedOutputStream output, File file, String name, String mime, boolean inline) throws IOException {
        output.write(("HTTP/1.1 200 OK" + CRLF).getBytes(StandardCharsets.ISO_8859_1));
        output.write(("Content-Type: " + mime + CRLF).getBytes(StandardCharsets.ISO_8859_1));
        output.write(("Content-Length: " + file.length() + CRLF).getBytes(StandardCharsets.ISO_8859_1));
        output.write(("Content-Disposition: " + (inline ? "inline" : "attachment") + "; filename=\"" + safeHeader(name) + "\"" + CRLF).getBytes(StandardCharsets.ISO_8859_1));
        output.write(("Cache-Control: no-store" + CRLF + CRLF).getBytes(StandardCharsets.ISO_8859_1));
        try (FileInputStream in = new FileInputStream(file)) { copy(in, output); }
        output.flush();
        if (name.startsWith("shared-") && name.endsWith(".zip")) file.delete();
    }

    private JSONArray readNotes() {
        try { if (!notesFile.isFile()) return new JSONArray(); return new JSONArray(new String(Files.readAllBytes(notesFile.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONArray(); }
    }
    private void saveNotes(JSONArray notes) throws IOException {
        try {
            Files.write(notesFile.toPath(), notes.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("无法保存共享文本", e);
        }
    }
    private JSONObject readMetadata() {
        try { if (!metadataFile.isFile()) return new JSONObject(); return new JSONObject(new String(Files.readAllBytes(metadataFile.toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }
    private void saveMetadata(JSONObject metadata) throws IOException {
        try {
            Files.write(metadataFile.toPath(), metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("无法保存文件信息", e);
        }
    }

    private void cleanExpiredFiles() {
        synchronized (dataLock) {
            JSONObject metadata = readMetadata(); boolean changed = false; long now = System.currentTimeMillis();
            java.util.Iterator<String> keys = metadata.keys(); List<String> names = new ArrayList<>(); while (keys.hasNext()) names.add(keys.next());
            for (String name : names) {
                JSONObject item = metadata.optJSONObject(name); String expiry = item == null ? "" : item.optString("expiresAt", "");
                boolean expired = false; try { expired = !expiry.isEmpty() && Instant.parse(expiry).toEpochMilli() <= now; } catch (Exception e) { expired = !expiry.isEmpty(); }
                if (expired || !new File(filesDir, name).isFile()) { new File(filesDir, name).delete(); metadata.remove(name); changed = true; }
            }
            if (changed) try { saveMetadata(metadata); } catch (IOException ignored) { }
        }
    }

    private String uniqueFilename(String original) {
        String name = safeName(original); if (name.isEmpty()) name = "upload";
        File candidate = new File(filesDir, name); int index = 2;
        int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : "";
        while (candidate.exists()) candidate = new File(filesDir, stem + " (" + index++ + ")" + ext);
        return candidate.getName();
    }
    private String safeName(String input) { if (input == null) return ""; String name = new File(input).getName().replaceAll("[^\\p{L}\\p{N}._ ()+\\-\\[\\]{}@]", "_"); return name.replace("..", "_"); }
    private String safeHeader(String input) { return input.replace("\"", "'").replace("\r", "").replace("\n", ""); }
    private String guessMime(String name) { String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(name)); return mime == null ? "application/octet-stream" : mime; }
    private Map<String, String> params(String query) { Map<String, String> map = new HashMap<>(); for (String part : query.split("&")) { int x = part.indexOf('='); if (x > 0) try { map.put(URLDecoder.decode(part.substring(0, x), "UTF-8"), URLDecoder.decode(part.substring(x + 1), "UTF-8")); } catch (Exception ignored) { } } return map; }

    private byte[] readBody(InputStream input, long length) throws IOException {
        if (length <= 0) return new byte[0];
        if (length > Integer.MAX_VALUE) throw new IOException("请求过大");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(length, 1024 * 1024));
        byte[] buffer = new byte[8192]; long remaining = length; int n;
        while (remaining > 0 && (n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining))) >= 0) { out.write(buffer, 0, n); remaining -= n; }
        if (remaining != 0) throw new IOException("请求体不完整");
        return out.toByteArray();
    }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (Exception e) { return fallback; } }
    private static String readLine(InputStream input) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); int c; boolean lastCr = false; while ((c = input.read()) >= 0) { if (c == '\n') break; if (c != '\r') out.write(c); lastCr = c == '\r'; } return out.size() == 0 && c < 0 ? null : out.toString("ISO-8859-1"); }
    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n); }
    private static int indexOf(byte[] source, byte[] target, int from) { outer: for (int i = Math.max(0, from); i <= source.length - target.length; i++) { for (int j = 0; j < target.length; j++) if (source[i + j] != target[j]) continue outer; return i; } return -1; }

    private void sendUnauthorized(OutputStream output) throws IOException { output.write(("HTTP/1.1 401 Unauthorized" + CRLF + "WWW-Authenticate: Basic realm=\"Multiuser Share\"" + CRLF + "Content-Length: 0" + CRLF + CRLF).getBytes(StandardCharsets.ISO_8859_1)); output.flush(); }
    private void sendText(OutputStream output, int status, String text) throws IOException { sendBytes(output, status, text, "text/plain; charset=utf-8", false); }
    private void sendJson(OutputStream output, JSONObject value) throws IOException { sendBytes(output, 200, value.toString(), "application/json; charset=utf-8", false); }
    private void sendBytes(OutputStream output, int status, String body, String mime, boolean ignored) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); String reason = status == 200 ? "OK" : status == 204 ? "No Content" : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : status == 413 ? "Payload Too Large" : "Error"; output.write(("HTTP/1.1 " + status + " " + reason + CRLF + "Content-Type: " + mime + CRLF + "Content-Length: " + bytes.length + CRLF + "Cache-Control: no-store" + CRLF + "Access-Control-Allow-Origin: *" + CRLF + CRLF).getBytes(StandardCharsets.ISO_8859_1)); output.write(bytes); output.flush(); }

    private String htmlPage() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>多用户共享</title>"
                + "<style>body{font-family:system-ui,-apple-system,sans-serif;margin:0;background:#f4f6f8;color:#17202a}header{background:#0d47a1;color:white;padding:18px 16px;position:sticky;top:0;z-index:2}main{max-width:900px;margin:auto;padding:14px}.tabs{display:flex;gap:8px;margin-bottom:14px}.tabs button,.toolbar button,button{border:0;border-radius:6px;padding:10px 13px;background:#1565c0;color:#fff;font-size:14px}.tabs button.active{background:#0d47a1}.panel{background:white;border:1px solid #dfe5eb;border-radius:8px;padding:14px;margin-bottom:14px}.muted{color:#68737d;font-size:13px}.danger{background:#b3261e!important}.empty{padding:22px;text-align:center;color:#7b8790}.note{border:1px solid #e0e5ea;border-radius:7px;padding:12px;margin:9px 0}.note h3{margin:0 0 5px;font-size:16px}.note p{white-space:pre-wrap;margin:4px 0}.toolbar{display:flex;gap:8px;flex-wrap:wrap;margin:9px 0}textarea{width:100%;box-sizing:border-box;min-height:100px;padding:9px;border:1px solid #b8c2cc;border-radius:6px;font:inherit}input[type=file]{max-width:100%}.file-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}.file-grid .file{border:1px solid #e0e5ea;border-radius:7px;padding:8px;min-width:0}.file-list .file{display:grid;grid-template-columns:28px 40px 1fr auto;gap:7px;align-items:center;border-bottom:1px solid #edf0f2;padding:8px 0}.thumb{width:100%;height:110px;object-fit:cover;background:#eef2f5;border-radius:5px}.list-thumb{width:32px;height:32px;object-fit:cover;background:#eef2f5;border-radius:4px}.filename{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-grid .filename{white-space:normal;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}.view-toggle{float:right}.warning{background:#fff3cd;border:1px solid #ffe69c;color:#664d03;padding:10px;border-radius:6px}</style></head><body><header><strong>多用户共享</strong><div id=\"auth\" class=\"muted\" style=\"color:#d7e6ff\"></div></header><main><div id=\"warning\" class=\"warning\" hidden>公开访问：同一 Wi-Fi 的设备也可以访问。当前使用 HTTP，不适合高度敏感内容。</div><div class=\"tabs\"><button id=\"tabText\" class=\"active\" onclick=\"showTab('text')\">文本</button><button id=\"tabFiles\" onclick=\"showTab('files')\">文件</button></div>"
                + "<section id=\"textPanel\" class=\"panel\"><textarea id=\"noteText\" placeholder=\"输入要共享的文本\"></textarea><div class=\"toolbar\"><button onclick=\"addNote()\">保存文本</button><button class=\"danger\" onclick=\"deleteNotes()\">删除所选</button></div><div id=\"notes\"></div></section>"
                + "<section id=\"filesPanel\" class=\"panel\" hidden><input id=\"fileInput\" type=\"file\" multiple><label style=\"display:block;margin:10px 0\">过期分钟（留空永久） <input id=\"expiry\" type=\"number\" min=\"1\" style=\"width:120px\"></label><div class=\"toolbar\"><button onclick=\"upload()\">上传文件</button><button onclick=\"downloadZip()\">下载 ZIP</button><button class=\"danger\" onclick=\"deleteFiles()\">删除所选</button><span class=\"view-toggle\"><button onclick=\"setView('grid')\">网格</button><button onclick=\"setView('list')\">列表</button></span></div><div id=\"files\"></div></section></main>"
                + "<script>let state={notes:[],files:[]};let view=localStorage.getItem('share-view')||'grid';function esc(s){return String(s).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}async function api(url,opt){let r=await fetch(url,opt||{});if(!r.ok)throw new Error(await r.text());return r.status===204?{}:r.json()}async function refresh(){state=await api('/api/state');document.getElementById('auth').textContent=state.auth?'密码保护已启用':'公开访问';document.getElementById('warning').hidden=state.auth;renderNotes();renderFiles()}function showTab(t){document.getElementById('textPanel').hidden=t!=='text';document.getElementById('filesPanel').hidden=t!=='files';document.getElementById('tabText').classList.toggle('active',t==='text');document.getElementById('tabFiles').classList.toggle('active',t==='files')}function renderNotes(){let e=document.getElementById('notes');if(!state.notes.length){e.innerHTML='<div class=empty>暂无文本</div>';return}e.innerHTML=state.notes.map(n=>{let lines=n.text.split(/\\r?\\n/);let title=lines.shift()||'无标题文本';return '<article class=note><label><input class=noteCheck type=checkbox value=\"'+esc(n.id)+'\"> 选择</label><h3>'+esc(title)+'</h3><p>'+esc(lines.slice(0,3).join('\\n')||title)+'</p><div class=muted>'+n.text.length+' 字 · '+new Date(n.createdAt).toLocaleString()+'</div><div class=toolbar><button onclick=\"copyText('+JSON.stringify(n.text)+')\">复制</button><button onclick=\"this.closest(\'article\').querySelector(\'.full\').hidden=!this.closest(\'article\').querySelector(\'.full\').hidden\">展开全文</button></div><p class=full hidden>'+esc(n.text)+'</p></article>'}).join('')}function renderFiles(){let e=document.getElementById('files');if(!state.files.length){e.innerHTML='<div class=empty>暂无文件</div>';return}e.className=view==='list'?'file-list':'file-grid';e.innerHTML=state.files.map(f=>{let image=f.mime&&f.mime.startsWith('image/');let thumb=image?'<img class=\"'+(view==='list'?'list-thumb':'thumb')+'\" src=\"/preview?name='+encodeURIComponent(f.name)+'\">':'<div class=\"'+(view==='list'?'list-thumb':'thumb')+' style=\"display:grid;place-items:center;font-size:24px\">📄</div>';return '<div class=file><input class=fileCheck type=checkbox value=\"'+esc(f.name)+'\">'+thumb+'<div class=filename><a href=\"/download?name='+encodeURIComponent(f.name)+'\">'+esc(f.name)+'</a><div class=muted>'+formatSize(f.size)+(f.expiresAt?' · '+new Date(f.expiresAt).toLocaleString()+' 过期':'')+'</div></div>'+(view==='grid'?'':'<span></span>')+'</div>'}).join('')}function formatSize(n){let u=['B','KB','MB','GB'];let i=0;while(n>=1024&&i<3){n/=1024;i++}return (i?n.toFixed(1):Math.round(n))+' '+u[i]}function checked(cls){return [...document.querySelectorAll(cls+':checked')].map(x=>x.value)}async function addNote(){let text=document.getElementById('noteText').value;if(!text.trim())return;await api('/api/notes',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text})});document.getElementById('noteText').value='';refresh()}async function deleteNotes(){let ids=checked('.noteCheck');if(!ids.length)return;if(!confirm('确定删除所选文本？'))return;await api('/api/notes/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ids})});refresh()}function copyText(t){navigator.clipboard?navigator.clipboard.writeText(t):alert(t)}async function upload(){let files=document.getElementById('fileInput').files;if(!files.length)return;let fd=new FormData();for(let f of files){if(f.size>512*1024*1024){alert(f.name+' 超过 512 MB');return}fd.append('files',f)}let expiry=document.getElementById('expiry').value;if(expiry)fd.append('expiry',expiry);await api('/api/files/upload',{method:'POST',body:fd});document.getElementById('fileInput').value='';refresh()}async function deleteFiles(){let names=checked('.fileCheck');if(!names.length)return;if(!confirm('确定删除所选文件？'))return;await api('/api/files/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({names})});refresh()}async function downloadZip(){let names=checked('.fileCheck');if(!names.length){alert('请先选择文件');return}let r=await fetch('/api/files/zip',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({names})});let b=await r.blob();let a=document.createElement('a');a.href=URL.createObjectURL(b);a.download='shared-files.zip';a.click();URL.revokeObjectURL(a.href)}function setView(v){view=v;localStorage.setItem('share-view',v);renderFiles()}refresh().catch(e=>alert(e.message))</script></body></html>";
    }
}
