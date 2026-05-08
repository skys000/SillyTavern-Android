package com.jiuguan.sillytavern

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private var hasInjectedMobileCSS = false

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val uris = mutableListOf<Uri>()

            // Handle multiple file selection
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                // Single file selection
                data.data?.let { uris.add(it) }
            }

            if (uris.isNotEmpty()) {
                fileUploadCallback?.onReceiveValue(uris.toTypedArray())
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen on while chatting
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        setupWebView()

        val serverUrl = intent.getStringExtra("server_url") ?: "http://127.0.0.1:8000"
        webView.loadUrl(serverUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = false
            useWideViewPort = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100

            // Enable local storage for SillyTavern
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                injectMobileOptimizations(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    // Server might have stopped, show error
                    view?.loadData(
                        getErrorHtml(),
                        "text/html",
                        "UTF-8"
                    )
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep SillyTavern URLs in WebView, open others externally
                if (url.contains("127.0.0.1") || url.contains("localhost")) {
                    return false
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        // Register JavaScript interface for downloads
        webView.addJavascriptInterface(DownloadInterface(), "AndroidDownload")

        // Handle regular URL downloads via DownloadManager
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.addRequestHeader("User-Agent", userAgent)
                request.setTitle(fileName)
                request.setDescription("正在下载...")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "下载中: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            // Handle file uploads (character cards, backgrounds, world books, etc.)
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = callback
                try {
                    // Always use */* so the user can pick ANY file type.
                    // SillyTavern character cards can be .png or .json;
                    // world books are .json; backgrounds are images.
                    // Restricting MIME type causes files to be invisible.
                    val contentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // Support multiple selection if requested
                        if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }

                    val chooserIntent = Intent.createChooser(contentIntent, "\u9009\u62e9\u6587\u4ef6")
                    fileChooserLauncher.launch(chooserIntent)
                } catch (e: Exception) {
                    // Fallback: try simpler ACTION_GET_CONTENT
                    try {
                        val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        fileChooserLauncher.launch(Intent.createChooser(fallback, "\u9009\u62e9\u6587\u4ef6"))
                    } catch (e2: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        Toast.makeText(this@MainActivity, "\u65e0\u6cd5\u6253\u5f00\u6587\u4ef6\u9009\u62e9\u5668", Toast.LENGTH_SHORT).show()
                        return false
                    }
                }
                return true
            }

            // Handle fullscreen video/content
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
            }
        }
    }

    private fun injectMobileOptimizations(view: WebView?) {
        view ?: return

        // Inject mobile-friendly CSS
        val css = """
            /* ===== SillyTavern Mobile Optimization ===== */

            /* Prevent horizontal overflow */
            html, body {
                overflow-x: hidden !important;
                -webkit-overflow-scrolling: touch !important;
                overscroll-behavior: contain !important;
            }

            /* Make top bar more compact on mobile */
            #top-bar, .topBar {
                flex-wrap: nowrap !important;
                overflow-x: auto !important;
                -webkit-overflow-scrolling: touch !important;
                gap: 2px !important;
                padding: 4px 8px !important;
            }

            /* Smaller top icons for mobile */
            #top-bar .fa-solid, #top-bar .fa-fw,
            #top-settings-holder .fa-solid, #top-settings-holder .fa-fw,
            .topBar i {
                font-size: 16px !important;
            }

            #top-bar button, #top-settings-holder button,
            .topBar button {
                padding: 6px !important;
                min-width: 32px !important;
                min-height: 32px !important;
            }

            /* Chat area takes full width */
            #chat {
                max-width: 100% !important;
                padding: 8px !important;
            }

            /* Chat messages better spacing */
            .mes {
                padding: 10px 12px !important;
                margin: 4px 0 !important;
                border-radius: 12px !important;
            }

            .mes_text {
                font-size: 15px !important;
                line-height: 1.5 !important;
            }

            /* Input area optimization */
            #send_form, #form_sheld {
                padding: 6px 8px !important;
                gap: 4px !important;
            }

            #send_textarea {
                font-size: 16px !important;
                min-height: 40px !important;
                max-height: 120px !important;
                border-radius: 20px !important;
                padding: 10px 16px !important;
            }

            /* Send button bigger touch target */
            #send_but, .send_button {
                min-width: 44px !important;
                min-height: 44px !important;
                border-radius: 50% !important;
            }

            /* Side panels take full screen on mobile */
            #left-nav-panel, #right-nav-panel,
            .drawer-content {
                width: 100vw !important;
                max-width: 100vw !important;
            }

            /* Character list better touch targets */
            .character_select, .bogus_folder_select {
                padding: 10px 12px !important;
                min-height: 60px !important;
            }

            /* Popup/dialog improvements for mobile */
            .popup, .dialogue_popup, dialog {
                width: 95vw !important;
                max-width: 95vw !important;
                max-height: 90vh !important;
                margin: auto !important;
                border-radius: 16px !important;
            }

            /* Settings panels full width */
            .range-block, .wide100p {
                max-width: 100% !important;
            }

            /* Hide some desktop-only elements to save space */
            #version_display_welcome {
                font-size: 14px !important;
            }

            /* Scrollbar thin on mobile */
            ::-webkit-scrollbar {
                width: 3px !important;
            }
            ::-webkit-scrollbar-thumb {
                background: rgba(255,255,255,0.15) !important;
                border-radius: 2px !important;
            }

            /* Bottom input area safe area for phones with gesture nav */
            #form_sheld {
                padding-bottom: max(6px, env(safe-area-inset-bottom)) !important;
            }
        """.trimIndent().replace("\n", " ").replace("\"", "\\\"")

        // Inject JS to add the CSS and improve touch behavior
        val js = """
            (function() {
                if (document.getElementById('st-mobile-css')) return;

                var style = document.createElement('style');
                style.id = 'st-mobile-css';
                style.textContent = "$css";
                document.head.appendChild(style);

                // Add viewport meta if missing
                if (!document.querySelector('meta[name=viewport]')) {
                    var meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                    document.head.appendChild(meta);
                }

                // Prevent pull-to-refresh in the WebView
                document.body.style.overscrollBehavior = 'contain';

                // Fix: make sure chat scrolls smoothly
                var chat = document.getElementById('chat');
                if (chat) {
                    chat.style.webkitOverflowScrolling = 'touch';
                    chat.style.overscrollBehavior = 'contain';
                }

                console.log('[SillyTavern App] Mobile optimizations injected');
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)

        // Inject download interceptor
        injectDownloadInterceptor(view)
    }

    private fun injectDownloadInterceptor(view: WebView?) {
        view ?: return
        val downloadJs = """
            (function() {
                if (window._downloadInterceptorInstalled) return;
                window._downloadInterceptorInstalled = true;

                // Intercept <a> element click with blob URL + download attribute
                var origCreateElement = document.createElement.bind(document);
                document.createElement = function(tag) {
                    var el = origCreateElement(tag);
                    if (tag.toLowerCase() === 'a') {
                        var origClick = el.click.bind(el);
                        el.click = function() {
                            if (el.href && el.href.startsWith('blob:') && el.download) {
                                // Intercept blob download
                                var fileName = el.download || 'download';
                                fetch(el.href).then(function(r) { return r.blob(); }).then(function(blob) {
                                    var reader = new FileReader();
                                    reader.onloadend = function() {
                                        var base64 = reader.result.split(',')[1] || '';
                                        if (window.AndroidDownload) {
                                            window.AndroidDownload.saveFile(base64, fileName);
                                        }
                                    };
                                    reader.readAsDataURL(blob);
                                }).catch(function(e) {
                                    console.error('Download intercept failed:', e);
                                    origClick();
                                });
                                return;
                            }
                            origClick();
                        };
                    }
                    return el;
                };

                // Also intercept window.saveAs / FileSaver.js if used
                if (!window._origSaveAs && window.saveAs) {
                    window._origSaveAs = window.saveAs;
                    window.saveAs = function(blob, fileName) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1] || '';
                            if (window.AndroidDownload) {
                                window.AndroidDownload.saveFile(base64, fileName);
                            }
                        };
                        reader.readAsDataURL(blob);
                    };
                }

                // Intercept URL.createObjectURL to track blob URLs
                var origCreateObjectURL = URL.createObjectURL.bind(URL);
                var blobMap = {};
                URL.createObjectURL = function(blob) {
                    var url = origCreateObjectURL(blob);
                    blobMap[url] = blob;
                    return url;
                };

                // Listen for click on existing <a> elements with download attr
                document.addEventListener('click', function(e) {
                    var a = e.target.closest('a[download]');
                    if (a && a.href && a.href.startsWith('blob:')) {
                        e.preventDefault();
                        e.stopPropagation();
                        var fileName = a.download || 'download';
                        var blob = blobMap[a.href];
                        if (blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64 = reader.result.split(',')[1] || '';
                                if (window.AndroidDownload) {
                                    window.AndroidDownload.saveFile(base64, fileName);
                                }
                            };
                            reader.readAsDataURL(blob);
                        } else {
                            fetch(a.href).then(function(r) { return r.blob(); }).then(function(b) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var base64 = reader.result.split(',')[1] || '';
                                    if (window.AndroidDownload) {
                                        window.AndroidDownload.saveFile(base64, fileName);
                                    }
                                };
                                reader.readAsDataURL(b);
                            });
                        }
                    }
                }, true);

                console.log('[SillyTavern App] Download interceptor installed');
            })();
        """.trimIndent()

        view.evaluateJavascript(downloadJs, null)
    }

    private fun getErrorHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    font-family: sans-serif;
                    background: #1a1a2e;
                    color: #e0e0e0;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                    margin: 0;
                    padding: 20px;
                    box-sizing: border-box;
                    text-align: center;
                }
                h1 { color: #ff6b6b; font-size: 24px; }
                p { font-size: 16px; line-height: 1.6; color: #aaa; }
                .code {
                    background: #0d0d1a;
                    padding: 12px 16px;
                    border-radius: 8px;
                    font-family: monospace;
                    font-size: 14px;
                    color: #4ecdc4;
                    margin: 16px 0;
                    word-break: break-all;
                }
                button {
                    background: #4ecdc4;
                    color: #1a1a2e;
                    border: none;
                    padding: 14px 32px;
                    border-radius: 12px;
                    font-size: 16px;
                    font-weight: bold;
                    margin-top: 20px;
                    cursor: pointer;
                }
            </style>
        </head>
        <body>
            <h1>酒馆连接中断</h1>
            <p>SillyTavern 服务似乎已停止运行。<br>请回到 Termux 确认服务状态。</p>
            <div class="code">cd ~/SillyTavern && bash start.sh</div>
            <button onclick="location.reload()">重新连接</button>
        </body>
        </html>
        """.trimIndent()
    }

    // JavaScript interface for saving downloaded files
    inner class DownloadInterface {
        @JavascriptInterface
        fun saveFile(base64Data: String, fileName: String) {
            Thread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val downloadsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "SillyTavern"
                    )
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()

                    // Avoid overwriting: append timestamp if file exists
                    var targetFile = File(downloadsDir, fileName)
                    if (targetFile.exists()) {
                        val name = fileName.substringBeforeLast('.', fileName)
                        val ext = if (fileName.contains('.')) ".${fileName.substringAfterLast('.')}" else ""
                        targetFile = File(downloadsDir, "${name}_${System.currentTimeMillis()}${ext}")
                    }

                    FileOutputStream(targetFile).use { it.write(bytes) }

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "已保存到下载目录: ${targetFile.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "保存失败: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle back button: go back in WebView history
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
