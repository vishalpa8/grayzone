package com.grayzone.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class SharedFile(
    val name: String,
    val uri: Uri,
    val size: Long,
    val mimeType: String
)

class LocalShareServer(
    port: Int = 8765,
    private val context: Context, // Required to open ContentResolver stream
    // Shared secret required on every /api/ request. It travels in the QR-code
    // URL (?t=...) so only a device that scanned the code can read the clipboard
    // or list/download files; anyone else on the same WiFi is rejected.
    val token: String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
) : NanoHTTPD(port) {

    private val clipboardText = AtomicReference("")
    private val sharedFiles = CopyOnWriteArrayList<SharedFile>()
    private val gson = Gson()

    private fun isAuthorized(session: IHTTPSession): Boolean =
        session.parameters["t"]?.firstOrNull() == token

    fun setClipboardText(text: String) {
        clipboardText.set(text)
    }

    fun getClipboardText(): String {
        return clipboardText.get()
    }

    fun addFile(file: SharedFile) {
        sharedFiles.add(file)
    }

    fun removeFile(name: String) {
        sharedFiles.removeIf { it.name == name }
    }

    fun clearFiles() {
        sharedFiles.clear()
    }

    fun getSharedFiles(): List<SharedFile> {
        return sharedFiles.toList()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Data endpoints require the token that was embedded in the QR URL.
        // The HTML pages ("/", "/files") stay open so the browser can load and
        // then read the token from its own URL to authorize the API calls.
        if (uri.startsWith("/api/") && !isAuthorized(session)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        }

        return try {
            when {
                uri == "/" && method == Method.GET -> {
                    newFixedLengthResponse(Response.Status.OK, "text/html", getClipboardHtml())
                }
                uri == "/files" && method == Method.GET -> {
                    newFixedLengthResponse(Response.Status.OK, "text/html", getFilesHtml())
                }
                uri == "/api/clipboard" && method == Method.GET -> {
                    val json = gson.toJson(mapOf("text" to clipboardText.get()))
                    newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }
                uri == "/api/clipboard" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val bodyData = map["postData"]
                    if (bodyData != null) {
                        try {
                            val data = gson.fromJson(bodyData, Map::class.java) as Map<String, Any>
                            val text = data["text"] as? String ?: ""
                            setClipboardText(text)
                        } catch (e: Exception) {
                            // ignore parsing error
                        }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                }
                uri == "/api/files" && method == Method.GET -> {
                    val filesList = sharedFiles.map { 
                        mapOf("name" to it.name, "size" to it.size, "mimeType" to it.mimeType) 
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(filesList))
                }
                uri.startsWith("/api/files/") && method == Method.GET -> {
                    val filename = uri.substringAfterLast("/")
                    val file = sharedFiles.find { it.name == filename }
                    if (file != null) {
                        val inputStream = context.contentResolver.openInputStream(file.uri)
                        if (inputStream != null) {
                            newChunkedResponse(Response.Status.OK, file.mimeType, inputStream)
                        } else {
                            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found on device")
                        }
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                    }
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun getClipboardHtml(): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Grayzone - Clipboard</title>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap" rel="stylesheet">
            <style>
                :root {
                    --bg: #07070F;
                    --card: #0F0F1C;
                    --accent: #7C4DFF;
                    --accent-hover: #6c42e3;
                    --teal: #26C6A6;
                    --text: #F8F9FA;
                    --text-secondary: #9BA1A6;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: 'Inter', sans-serif;
                    background-color: var(--bg);
                    color: var(--text);
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                    padding: 20px;
                }
                .container {
                    background: var(--card);
                    border: 1px solid rgba(255, 255, 255, 0.05);
                    border-radius: 20px;
                    padding: 32px;
                    width: 100%;
                    max-width: 600px;
                    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.5);
                }
                .header { text-align: center; margin-bottom: 24px; }
                .title { font-size: 24px; font-weight: 600; margin-bottom: 8px; display: flex; align-items: center; justify-content: center; gap: 8px; }
                .title::before { content: ''; display: inline-block; width: 12px; height: 12px; background: var(--teal); border-radius: 50%; box-shadow: 0 0 10px var(--teal); }
                .subtitle { color: var(--text-secondary); font-size: 14px; }
                textarea {
                    width: 100%;
                    height: 200px;
                    background: rgba(255, 255, 255, 0.03);
                    border: 1px solid rgba(255, 255, 255, 0.1);
                    border-radius: 12px;
                    color: var(--text);
                    padding: 16px;
                    font-family: inherit;
                    font-size: 16px;
                    resize: vertical;
                    transition: border-color 0.3s;
                }
                textarea:focus { outline: none; border-color: var(--accent); }
                .actions { display: flex; gap: 16px; margin-top: 24px; }
                button {
                    flex: 1;
                    padding: 14px 24px;
                    border: none;
                    border-radius: 12px;
                    font-size: 16px;
                    font-weight: 500;
                    cursor: pointer;
                    transition: all 0.3s;
                    font-family: inherit;
                }
                .btn-primary { background: var(--accent); color: white; }
                .btn-primary:hover { background: var(--accent-hover); transform: translateY(-2px); }
                .btn-secondary { background: rgba(255, 255, 255, 0.1); color: var(--text); }
                .btn-secondary:hover { background: rgba(255, 255, 255, 0.15); }
                .nav { display: flex; justify-content: center; margin-top: 24px; }
                .nav a { color: var(--text-secondary); text-decoration: none; font-size: 14px; transition: color 0.3s; }
                .nav a:hover { color: var(--accent); }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="title">Connected to Grayzone</div>
                    <div class="subtitle">Clipboard Sharing</div>
                </div>
                <textarea id="clipboardText" placeholder="Clipboard is empty..."></textarea>
                <div class="actions">
                    <button class="btn-secondary" onclick="fetchClipboard()">Refresh</button>
                    <button class="btn-primary" onclick="sendClipboard()">Send to Phone</button>
                </div>
                <div class="nav">
                    <a href="/files">Go to File Share &rarr;</a>
                </div>
            </div>

            <script>
                const token = new URLSearchParams(window.location.search).get('t') || '';
                const q = token ? ('?t=' + encodeURIComponent(token)) : '';

                async function fetchClipboard() {
                    try {
                        const res = await fetch('/api/clipboard' + q);
                        const data = await res.json();
                        document.getElementById('clipboardText').value = data.text || '';
                    } catch (e) {
                        console.error('Failed to fetch clipboard', e);
                    }
                }
                
                async function sendClipboard() {
                    const text = document.getElementById('clipboardText').value;
                    const btn = document.querySelector('.btn-primary');
                    const originalText = btn.innerText;
                    
                    try {
                        btn.innerText = 'Sending...';
                        await fetch('/api/clipboard' + q, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ text })
                        });
                        btn.innerText = 'Sent!';
                        setTimeout(() => btn.innerText = originalText, 2000);
                    } catch (e) {
                        btn.innerText = 'Error';
                        setTimeout(() => btn.innerText = originalText, 2000);
                    }
                }

                setInterval(fetchClipboard, 2000);
                fetchClipboard();
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun getFilesHtml(): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Grayzone - Files</title>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&display=swap" rel="stylesheet">
            <style>
                :root {
                    --bg: #07070F;
                    --card: #0F0F1C;
                    --accent: #7C4DFF;
                    --teal: #26C6A6;
                    --text: #F8F9FA;
                    --text-secondary: #9BA1A6;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: 'Inter', sans-serif;
                    background-color: var(--bg);
                    color: var(--text);
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                    padding: 20px;
                }
                .container {
                    background: var(--card);
                    border: 1px solid rgba(255, 255, 255, 0.05);
                    border-radius: 20px;
                    padding: 32px;
                    width: 100%;
                    max-width: 600px;
                    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.5);
                }
                .header { text-align: center; margin-bottom: 24px; }
                .title { font-size: 24px; font-weight: 600; margin-bottom: 8px; display: flex; align-items: center; justify-content: center; gap: 8px; }
                .title::before { content: ''; display: inline-block; width: 12px; height: 12px; background: var(--teal); border-radius: 50%; box-shadow: 0 0 10px var(--teal); }
                .subtitle { color: var(--text-secondary); font-size: 14px; }
                .file-list {
                    background: rgba(255, 255, 255, 0.02);
                    border-radius: 12px;
                    overflow: hidden;
                    margin-bottom: 24px;
                    max-height: 300px;
                    overflow-y: auto;
                }
                .file-item {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    padding: 16px;
                    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
                    transition: background 0.3s;
                }
                .file-item:last-child { border-bottom: none; }
                .file-item:hover { background: rgba(255, 255, 255, 0.05); }
                .file-name { font-weight: 500; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 250px; }
                .file-size { color: var(--text-secondary); font-size: 13px; }
                .btn-download {
                    background: var(--accent);
                    color: white;
                    text-decoration: none;
                    padding: 8px 16px;
                    border-radius: 8px;
                    font-size: 14px;
                    font-weight: 500;
                    transition: background 0.3s;
                }
                .btn-download:hover { background: #6c42e3; }
                .empty-state { text-align: center; padding: 40px 20px; color: var(--text-secondary); }
                .nav { display: flex; justify-content: center; margin-top: 24px; }
                .nav a { color: var(--text-secondary); text-decoration: none; font-size: 14px; transition: color 0.3s; }
                .nav a:hover { color: var(--accent); }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="title">Connected to Grayzone</div>
                    <div class="subtitle">Shared Files</div>
                </div>
                
                <div class="file-list" id="fileList">
                    <div class="empty-state">Loading files...</div>
                </div>

                <div class="nav">
                    <a href="/">&larr; Back to Clipboard</a>
                </div>
            </div>

            <script>
                function formatBytes(bytes, decimals = 2) {
                    if (bytes === 0) return '0 Bytes';
                    const k = 1024, dm = decimals < 0 ? 0 : decimals, sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'], i = Math.floor(Math.log(bytes) / Math.log(k));
                    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
                }

                const token = new URLSearchParams(window.location.search).get('t') || '';
                const q = token ? ('?t=' + encodeURIComponent(token)) : '';

                async function fetchFiles() {
                    try {
                        const res = await fetch('/api/files' + q);
                        const files = await res.json();
                        const list = document.getElementById('fileList');
                        
                        if (files.length === 0) {
                            list.innerHTML = '<div class="empty-state">No files shared yet.</div>';
                            return;
                        }
                        
                        let html = '';
                        files.forEach(file => {
                            html += `
                                <div class="file-item">
                                    <div>
                                        <div class="file-name">${'$'}{file.name}</div>
                                        <div class="file-size">${'$'}{formatBytes(file.size)}</div>
                                    </div>
                                    <a href="/api/files/${'$'}{encodeURIComponent(file.name)}${'$'}{q}" class="btn-download" download>Download</a>
                                </div>
                            `;
                        });
                        list.innerHTML = html;
                    } catch (e) {
                        console.error('Failed to fetch files', e);
                    }
                }

                setInterval(fetchFiles, 3000);
                fetchFiles();
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
