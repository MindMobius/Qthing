@file:android.annotation.SuppressLint("JavascriptInterface")

package de.xianmu.qthing

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val QTHING_TAG = "QTHING"

private fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT
    val model = Build.MODEL
    val manufacturer = Build.MANUFACTURER
    val brand = Build.BRAND
    val device = Build.DEVICE
    val product = Build.PRODUCT
    val hardware = Build.HARDWARE
    return fingerprint.startsWith("generic") ||
        fingerprint.startsWith("unknown") ||
        model.contains("google_sdk", ignoreCase = true) ||
        model.contains("Emulator", ignoreCase = true) ||
        model.contains("Android SDK built for", ignoreCase = true) ||
        manufacturer.contains("Genymotion", ignoreCase = true) ||
        (brand.startsWith("generic") && device.startsWith("generic")) ||
        product.contains("google_sdk", ignoreCase = true) ||
        product.contains("sdk_gphone", ignoreCase = true) ||
        product.contains("sdk", ignoreCase = true) ||
        product.contains("emulator", ignoreCase = true) ||
        product.contains("simulator", ignoreCase = true) ||
        hardware.contains("ranchu", ignoreCase = true) ||
        hardware.contains("goldfish", ignoreCase = true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            QthingTheme {
                QthingApp()
            }
        }
    }
}

@Composable
private fun QthingTheme(
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val scheme = if (isDark) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            secondary = Color.White,
            onSecondary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF141414),
            onSurfaceVariant = Color.White,
            outline = Color(0xFF3A3A3A),
        )
    } else {
        lightColorScheme(
            primary = Color.Black,
            onPrimary = Color.White,
            secondary = Color.Black,
            onSecondary = Color.White,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF4F4F4),
            onSurfaceVariant = Color.Black,
            outline = Color(0xFFBDBDBD),
        )
    }

    MaterialTheme(colorScheme = scheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Composable
private fun QthingApp() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val preferencesStore = remember(appContext) { PreferencesStore(appContext) }
    val notesTreeUriString by preferencesStore.notesTreeUriFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    var reSelectRequested by rememberSaveable { mutableStateOf(false) }

    if (notesTreeUriString == null || reSelectRequested) {
        FolderPickerScreen(
            onFolderPicked = { uri ->
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                }
                scope.launch {
                    preferencesStore.setNotesTreeUri(uri.toString())
                    preferencesStore.setLastOpenedFileUri(null)
                    reSelectRequested = false
                }
            },
        )
        return
    }

    val notesTreeUriStringValue = notesTreeUriString ?: return
    val notesTreeUri = Uri.parse(notesTreeUriStringValue)
    val repository = remember(notesTreeUri) { NotesRepository(appContext, notesTreeUri) }
    WebAppScreen(
        context = context,
        appContext = appContext,
        repository = repository,
        preferencesStore = preferencesStore,
        isDark = isDark,
        onRequestReSelectFolder = { reSelectRequested = true },
    )
}

private class AndroidFsBridge(
    private val appContext: Context,
    private val repository: NotesRepository,
    private val preferencesStore: PreferencesStore,
    private val onRequestReSelectFolder: () -> Unit,
) {
    private val writeLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun call(requestJson: String): String {
        val req =
            try {
                JSONObject(requestJson)
            } catch (_: Exception) {
                return JSONObject().put("id", "").put("ok", false).put("message", "请求格式错误").toString()
            }

        val id = req.optString("id", "")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()

        fun ok(result: Any?): String {
            return JSONObject()
                .put("id", id)
                .put("ok", true)
                .put("result", result)
                .toString()
        }

        fun err(message: String): String {
            return JSONObject()
                .put("id", id)
                .put("ok", false)
                .put("message", message)
                .toString()
        }

        return try {
            when (method) {
                "getState" -> {
                    val rootUriString =
                        try {
                            repository.resolveRootDirectory()?.uri?.toString()
                        } catch (_: SecurityException) {
                            return err("目录权限失效，请重新选择目录")
                        }

                    val last =
                        try {
                            runBlocking { preferencesStore.lastOpenedFileUriFlow.first() }
                        } catch (_: Exception) {
                            null
                        }
                    ok(
                        JSONObject()
                            .put("rootUri", rootUriString)
                            .put("lastOpenedFileUri", last),
                    )
                }

                "setLastOpenedFileUri" -> {
                    val uri = params.optString("uri", "").trim().ifEmpty { null }
                    runBlocking { preferencesStore.setLastOpenedFileUri(uri) }
                    ok(JSONObject())
                }

                "stat" -> {
                    val uriString = params.optString("uri", "")
                    val doc = repository.resolveByUri(Uri.parse(uriString))
                    ok(
                        JSONObject()
                            .put("exists", doc?.exists() == true)
                            .put("isFile", doc?.isFile == true)
                            .put("isDir", doc?.isDirectory == true)
                            .put("name", doc?.name),
                    )
                }

                "listChildren" -> {
                    val uriString = params.optString("uri", "")
                    val children = repository.listChildrenByUri(Uri.parse(uriString))
                    val arr = org.json.JSONArray()
                    for (c in children) {
                        arr.put(
                            JSONObject()
                                .put("uri", c.uri.toString())
                                .put("name", c.name)
                                .put("isDir", c.isDirectory),
                        )
                    }
                    ok(arr)
                }

                "readText" -> {
                    val uriString = params.optString("uri", "")
                    val text = repository.readText(Uri.parse(uriString))
                    ok(text)
                }

                "writeText" -> {
                    val uriString = params.optString("uri", "")
                    val text = params.optString("text", "")
                    synchronized(writeLock) {
                        repository.writeText(Uri.parse(uriString), text)
                    }
                    ok(JSONObject())
                }

                "createInRoot" -> {
                    val file = repository.createNewNoteInRoot() ?: return err("新建失败")
                    ok(
                        JSONObject()
                            .put("uri", file.uri.toString())
                            .put("name", file.name),
                    )
                }

                "rename" -> {
                    val uriString = params.optString("uri", "")
                    val name = params.optString("name", "")
                    val newUri = repository.renameDocument(Uri.parse(uriString), name) ?: return err("重命名失败")
                    val newDoc = repository.resolveByUri(newUri)
                    ok(
                        JSONObject()
                            .put("uri", newUri.toString())
                            .put("name", newDoc?.name ?: name),
                    )
                }

                "delete" -> {
                    val uriString = params.optString("uri", "")
                    val success = repository.deleteDocument(Uri.parse(uriString))
                    if (!success) return err("删除失败")
                    ok(JSONObject())
                }

                "requestReSelectFolder" -> {
                    mainHandler.post { onRequestReSelectFolder() }
                    ok(JSONObject())
                }

                else -> err("未知方法：$method")
            }
        } catch (e: Exception) {
            val uri = params.optString("uri", "")
            val msg = e.message ?: e.toString()
            Log.e(QTHING_TAG, "bridge call failed method=$method uri=$uri", e)
            err("$method: ${e.javaClass.simpleName}: $msg")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebAppScreen(
    context: Context,
    appContext: Context,
    repository: NotesRepository,
    preferencesStore: PreferencesStore,
    isDark: Boolean,
    onRequestReSelectFolder: () -> Unit,
) {
    val bridge =
        remember(repository, preferencesStore) {
            AndroidFsBridge(
                appContext = appContext,
                repository = repository,
                preferencesStore = preferencesStore,
                onRequestReSelectFolder = onRequestReSelectFolder,
            )
        }

    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    fun eval(webView: WebView?, js: String) {
        if (webView == null) return
        webView.post { webView.evaluateJavascript(js, null) }
    }

    var bannerVisible by remember { mutableStateOf(true) }
    var bannerText by remember { mutableStateOf("Web 正在加载…") }

    fun logD(message: String) {
        Log.d(QTHING_TAG, message)
    }

    fun logE(message: String, e: Throwable? = null) {
        if (e != null) Log.e(QTHING_TAG, message, e) else Log.e(QTHING_TAG, message)
    }

    val assetLoader =
        remember(appContext) {
            WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
                .build()
        }

    val webView =
        remember {
            WebView(context).apply {
                webViewRef.value = this
                WebView.setWebContentsDebuggingEnabled(true)
                setBackgroundColor(android.graphics.Color.BLACK)
                val emulator = isProbablyEmulator()
                logD(
                    "device fingerprint=${Build.FINGERPRINT} model=${Build.MODEL} product=${Build.PRODUCT} hardware=${Build.HARDWARE} emulator=$emulator",
                )
                if (emulator) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    logD("web layer=SOFTWARE (emulator)")
                } else {
                    setLayerType(View.LAYER_TYPE_NONE, null)
                    logD("web layer=NONE")
                }
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                        v.requestFocus()
                    }
                    false
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            logD(
                                "web console: ${consoleMessage.messageLevel()} ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                            )
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = assetLoader.shouldInterceptRequest(request.url)

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            bannerVisible = true
                            bannerText = "Web 正在加载…"
                            logD("web onPageStarted $url")
                        }

                        override fun onPageCommitVisible(view: WebView, url: String) {
                            super.onPageCommitVisible(view, url)
                            logD("web onPageCommitVisible $url")
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            eval(view, "window.App && window.App.setTheme(${if (isDark) "true" else "false"})")
                            logD("web onPageFinished $url")
                            view.evaluateJavascript(
                                "(function(){try{var r=document.getElementById('root');var b=document.getElementById('boot');return JSON.stringify({ready:document.readyState,root:!!r,rootChildren:r?r.children.length:0,boot:b?b.style.display:null,href:location.href});}catch(e){return 'E:'+e.name+':'+e.message}})()",
                            ) { value ->
                                logD("web domState=$value")
                                val keepBanner =
                                    value == null ||
                                        value.startsWith("\"E:") ||
                                        value.contains("\"rootChildren\":0")
                                if (keepBanner) {
                                    bannerVisible = true
                                    bannerText = "Web 已加载但 UI 未就绪（请看 Logcat 过滤 QTHING）"
                                } else {
                                    bannerVisible = false
                                }
                            }

                            view.evaluateJavascript(
                                "(function(){try{var a=document.querySelector('.app');if(!a)return 'no-app';var s=getComputedStyle(a);var r=a.getBoundingClientRect();return JSON.stringify({display:s.display,vis:s.visibility,op:s.opacity,bg:s.backgroundColor,w:r.width,h:r.height});}catch(e){return 'E:'+e.name+':'+e.message}})()",
                            ) { value ->
                                logD("web appBox=$value")
                            }

                            view.postDelayed(
                                {
                                    view.evaluateJavascript(
                                        "(function(){try{var a=document.querySelector('.app');if(!a)return 'no-app';var r=a.getBoundingClientRect();return JSON.stringify({w:r.width,h:r.height,innerH:window.innerHeight,docH:document.documentElement.getBoundingClientRect().height,bodyH:document.body.getBoundingClientRect().height,rootH:document.getElementById('root').getBoundingClientRect().height});}catch(e){return 'E:'+e.name+':'+e.message}})()",
                                    ) { value ->
                                        logD("web sizes@500ms=$value")
                                    }
                                },
                                500,
                            )
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (!request.isForMainFrame) return
                            val msg = error.description?.toString() ?: "加载失败"
                            bannerVisible = true
                            bannerText = "Web 加载失败：$msg"
                            logE("web onReceivedError mainFrame url=${request.url} $msg")
                            view.loadDataWithBaseURL(
                                null,
                                "<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><body style='margin:0;display:grid;place-items:center;height:100vh;background:#000;color:#fff;font:16px/1.4 system-ui, -apple-system, Segoe UI, Roboto, sans-serif'>Web 加载失败：${msg}</body>",
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    }
                addJavascriptInterface(bridge, "AndroidFs")
                loadUrl("https://appassets.androidplatform.net/assets/app/index.html")
            }
        }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(isDark) {
        eval(webViewRef.value, "window.App && window.App.setTheme(${if (isDark) "true" else "false"})")
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        if (bannerVisible) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    text = bannerText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun FolderPickerScreen(
    onFolderPicked: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            onFolderPicked(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "选择一个笔记文件夹（推荐选择 Obsidian Vault）",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = { picker.launch(null) }) {
                Text(text = "选择文件夹")
            }
        }
    }
}

private data class FileItem(
    val uriString: String,
    val name: String,
    val isDirectory: Boolean,
)

private data class DrawerRow(
    val item: FileItem,
    val indent: Int,
)

@Composable
private fun DrawerContent(
    notesTreeUri: Uri,
    repository: NotesRepository,
    currentFileUriString: String?,
    treeRefreshNonce: Int,
    shouldLoadTree: Boolean,
    onCreateNewNote: () -> Unit,
    onOpenFile: (uriString: String, name: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val childrenCache = remember { androidx.compose.runtime.mutableStateMapOf<String, List<FileItem>>() }
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val loading = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val lastSeenRefreshNonce = remember { mutableStateOf(0) }

    fun ensureLoaded(directoryUriString: String) {
        if (childrenCache.containsKey(directoryUriString)) return
        if (loading[directoryUriString] == true) return
        loading[directoryUriString] = true
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                val uri = Uri.parse(directoryUriString)
                val dir = DocumentFile.fromTreeUri(appContext, uri) ?: DocumentFile.fromSingleUri(appContext, uri)
                if (dir == null || !dir.isDirectory) return@withContext emptyList<FileItem>()

                repository.listChildren(dir).mapNotNull { f ->
                    val name = f.name ?: return@mapNotNull null
                    FileItem(
                        uriString = f.uri.toString(),
                        name = name,
                        isDirectory = f.isDirectory,
                    )
                }
            }
            childrenCache[directoryUriString] = items
            loading[directoryUriString] = false
        }
    }

    fun buildRows(directoryUriString: String, indent: Int): List<DrawerRow> {
        val rows = mutableListOf<DrawerRow>()
        val children = childrenCache[directoryUriString].orEmpty()
        for (child in children) {
            rows += DrawerRow(child, indent)
            if (child.isDirectory && expanded[child.uriString] == true) {
                rows += buildRows(child.uriString, indent + 1)
            }
        }
        return rows
    }

    val rootUriString = notesTreeUri.toString()

    LaunchedEffect(rootUriString, shouldLoadTree, treeRefreshNonce) {
        if (treeRefreshNonce != lastSeenRefreshNonce.value) {
            childrenCache.clear()
            expanded.clear()
            loading.clear()
            lastSeenRefreshNonce.value = treeRefreshNonce
        }
        expanded[rootUriString] = true
        if (shouldLoadTree) {
            ensureLoaded(rootUriString)
        }
    }

    val rows = buildRows(rootUriString, indent = 0)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Qthing", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onOpenSettings) {
                Text(text = "设置")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Button(onClick = onCreateNewNote, modifier = Modifier.fillMaxWidth()) {
                Text(text = "新建")
            }
        }

        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            if (loading[rootUriString] == true && childrenCache[rootUriString].isNullOrEmpty()) {
                item {
                    Text(
                        text = "加载中…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            items(rows, key = { it.item.uriString }) { row ->
                val isSelected = row.item.uriString == currentFileUriString
                val cardColors =
                    if (isSelected) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    }

                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .padding(start = (row.indent * 12).dp)
                        .clickable {
                            if (row.item.isDirectory) {
                                val next = !(expanded[row.item.uriString] == true)
                                expanded[row.item.uriString] = next
                                if (next) ensureLoaded(row.item.uriString)
                            } else {
                                onOpenFile(row.item.uriString, row.item.name)
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (row.item.isDirectory) "▸" else " ",
                            modifier = Modifier.width(18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = row.item.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    repository: NotesRepository,
    preferencesStore: PreferencesStore,
    currentFileUriString: String?,
    currentFileName: String?,
    onOpenDrawer: () -> Unit,
    onFileUriChanged: (String) -> Unit,
    onFileRenamed: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val saveMutex = remember { Mutex() }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    var title by rememberSaveable { mutableStateOf(currentFileName ?: "未命名") }
    var content by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var openedFileUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var webReady by remember { mutableStateOf(false) }
    var pendingText by remember { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var renameDialogOpen by rememberSaveable { mutableStateOf(false) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    fun eval(webView: WebView?, js: String) {
        if (webView == null) return
        webView.post { webView.evaluateJavascript(js, null) }
    }

    fun applyTheme(webView: WebView?) {
        eval(webView, "window.Editor && window.Editor.setTheme(${if (isDark) "true" else "false"})")
    }

    fun applyStatus(webView: WebView?, text: String) {
        eval(webView, "window.Editor && window.Editor.setStatus(${JSONObject.quote(text)})")
    }

    fun applyText(webView: WebView?, text: String) {
        eval(webView, "window.Editor && window.Editor.setText(${JSONObject.quote(text)})")
    }

    fun focus(webView: WebView?) {
        eval(webView, "window.Editor && window.Editor.focus()")
    }

    val bridge = remember {
        EditorJsBridge(
            onReady = {
                mainHandler.post { webReady = true }
            },
            onTextChanged = { text ->
                mainHandler.post {
                    content = text
                    dirty = true

                    val uriString = openedFileUriString ?: return@post
                    saveJob?.cancel()
                    saveJob =
                        scope.launch {
                            delay(50)
                            saving = true
                            saveError = null
                            applyStatus(webViewRef.value, "保存中…")
                            try {
                                val snapshot = content
                                saveMutex.withLock {
                                    withContext(Dispatchers.IO) {
                                        repository.writeText(Uri.parse(uriString), snapshot)
                                    }
                                }
                                dirty = false
                                applyStatus(webViewRef.value, "已保存")
                            } catch (e: Exception) {
                                saveError = e.message ?: "保存失败"
                                applyStatus(webViewRef.value, "保存失败")
                            } finally {
                                saving = false
                            }
                        }
                }
            },
        )
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun buildWebView(): WebView {
        return WebView(context).apply {
            webViewRef.value = this
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_NONE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                    v.requestFocus()
                }
                false
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                    }
                }
            addJavascriptInterface(bridge, "Android")
            loadUrl("file:///android_asset/editor/index.html")
        }
    }

    val webView =
        remember {
            buildWebView()
        }

    DisposableEffect(webView) {
        onDispose {
            webView.destroy()
        }
    }

    LaunchedEffect(webReady, isDark) {
        if (webReady) {
            applyTheme(webView)
        }
    }

    LaunchedEffect(currentFileUriString, currentFileName) {
        loaded = false
        saving = false
        saveError = null
        saveJob?.cancel()
        saveJob = null

        val newUriString = currentFileUriString ?: return@LaunchedEffect
        val previousUriString = openedFileUriString
        if (previousUriString != null && previousUriString != newUriString && dirty) {
            try {
                withContext(Dispatchers.IO) { repository.writeText(Uri.parse(previousUriString), content) }
            } catch (_: Exception) {
            }
        }

        openedFileUriString = newUriString

        val newTitle =
            currentFileName
                ?: withContext(Dispatchers.IO) { repository.resolveDocument(Uri.parse(newUriString)) }?.name
                ?: "未命名"
        title = newTitle

        val text = withContext(Dispatchers.IO) { repository.readText(Uri.parse(newUriString)) }
        content = text
        loaded = true
        dirty = false

        if (webReady) {
            applyTheme(webView)
            applyText(webView, text)
            focus(webView)
        } else {
            pendingText = text
        }
    }

    LaunchedEffect(webReady) {
        if (!webReady) return@LaunchedEffect
        val text = pendingText ?: return@LaunchedEffect
        pendingText = null
        applyTheme(webView)
        applyText(webView, text)
        focus(webView)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onOpenDrawer) {
                        Text(text = "≡")
                    }
                },
                title = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val statusText = when {
                            saveError != null -> saveError ?: "保存失败"
                            saving -> "保存中…"
                            dirty -> "编辑中…"
                            loaded -> "已保存"
                            else -> "加载中…"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val baseName = title.removeSuffix(".md")
                            renameValue = baseName
                            renameDialogOpen = true
                        },
                        enabled = currentFileUriString != null,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(text = "重命名")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            update = {
                if (webReady) {
                    applyTheme(webView)
                }
            },
        )
    }

    if (renameDialogOpen) {
        AlertDialog(
            onDismissRequest = { renameDialogOpen = false },
            title = { Text(text = "重命名") },
            text = {
                TextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    placeholder = { Text(text = "文件名（不含 .md）") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uriString = openedFileUriString
                        if (uriString == null) {
                            renameDialogOpen = false
                            return@TextButton
                        }
                        scope.launch {
                            saving = true
                            saveError = null
                            applyStatus(webView, "保存中…")
                            val snapshot = content
                            try {
                                saveMutex.withLock {
                                    withContext(Dispatchers.IO) { repository.writeText(Uri.parse(uriString), snapshot) }
                                }
                            } catch (_: Exception) {}
                            val newUri = withContext(Dispatchers.IO) {
                                repository.renameDocument(Uri.parse(uriString), renameValue)
                            }
                            if (newUri != null) {
                                val newName = (renameValue.trim().ifEmpty { "未命名" })
                                    .let { if (it.endsWith(".md", ignoreCase = true)) it else "$it.md" }
                                val newUriString = newUri.toString()
                                openedFileUriString = newUriString
                                onFileUriChanged(newUriString)
                                title = newName
                                onFileRenamed(newName)
                                preferencesStore.setLastOpenedFileUri(newUriString)
                                applyStatus(webView, "已保存")
                            } else {
                                saveError = "重命名失败"
                                applyStatus(webView, "重命名失败")
                            }
                            renameDialogOpen = false
                            saving = false
                        }
                    },
                ) {
                    Text(text = "确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogOpen = false }) {
                    Text(text = "取消")
                }
            },
        )
    }
}

private class EditorJsBridge(
    private val onReady: () -> Unit,
    private val onTextChanged: (String) -> Unit,
) {
    @JavascriptInterface
    fun onReady() {
        onReady()
    }

    @JavascriptInterface
    fun onTextChanged(text: String) {
        onTextChanged(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    notesTreeUriString: String,
    onOpenDrawer: () -> Unit,
    onBackToEditor: () -> Unit,
    onReSelectFolder: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onOpenDrawer) {
                        Text(text = "≡")
                    }
                },
                title = { Text(text = "设置") },
                actions = {
                    TextButton(onClick = onBackToEditor) {
                        Text(text = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(text = "当前笔记目录：", style = MaterialTheme.typography.titleSmall)
            Text(
                text = notesTreeUriString,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 16.dp)) {
                Button(onClick = onReSelectFolder) {
                    Text(text = "重新选择目录")
                }
            }
        }
    }
}
