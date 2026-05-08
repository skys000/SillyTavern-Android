package com.jiuguan.sillytavern

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Manages Node.js runtime and SillyTavern server lifecycle.
 *
 * Node.js binary: placed in jniLibs/arm64-v8a/libnode.so, Android extracts
 * it to nativeLibraryDir with execute permission automatically.
 *
 * SillyTavern: extracted from assets/sillytavern.zip to filesDir/sillytavern/
 */
class NodeManager(private val context: Context) {

    companion object {
        private const val TAG = "NodeManager"
        const val SERVER_PORT = 8000
        const val SERVER_HOST = "127.0.0.1"

        private const val ST_DIR = "sillytavern"
        private const val ST_SERVER = "sillytavern/server.js"
        private const val VERSION_FILE = "v2_version.txt"

        // Current bundle version - bump this to force re-extraction
        const val BUNDLE_VERSION = "1"
    }

    private var nodeProcess: Process? = null
    private val processOutput = mutableListOf<String>()

    val serverUrl: String get() = "http://$SERVER_HOST:$SERVER_PORT"

    /** Base directory for all V2 files */
    val baseDir: File get() = context.filesDir

    /** Node.js binary path - from nativeLibraryDir (has execute permission) */
    val nodeBinary: File get() = File(context.applicationInfo.nativeLibraryDir, "libnode.so")

    /** SillyTavern directory */
    val stDir: File get() = File(baseDir, ST_DIR)

    /** SillyTavern user data directory */
    val stDataDir: File get() = File(stDir, "data")

    /** Directory for Node.js shared libraries (extracted from assets) */
    val nodeLibsDir: File get() = File(baseDir, "nodelibs")

    /** Check if Node.js binary exists and is executable */
    fun isNodeInstalled(): Boolean {
        val exists = nodeBinary.exists()
        val canExec = nodeBinary.canExecute()
        Log.i(TAG, "Node check: path=${nodeBinary.absolutePath}, exists=$exists, canExecute=$canExec")
        return exists && canExec
    }

    /** Check if Node.js shared libraries are extracted */
    fun areNodeLibsInstalled(): Boolean {
        return nodeLibsDir.exists() && (nodeLibsDir.list()?.isNotEmpty() == true)
    }

    /** Check if SillyTavern is extracted */
    fun isSillyTavernInstalled(): Boolean {
        return File(baseDir, ST_SERVER).exists()
    }

    /** Check if everything is ready to run */
    fun isReady(): Boolean {
        return isNodeInstalled() && areNodeLibsInstalled() && isSillyTavernInstalled()
    }

    /** Check if the installed version matches the bundled version */
    fun isUpToDate(): Boolean {
        val versionFile = File(baseDir, VERSION_FILE)
        if (!versionFile.exists()) return false
        return try {
            versionFile.readText().trim() == BUNDLE_VERSION
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract SillyTavern from assets.
     * Expected asset: sillytavern.zip containing server.js, node_modules, etc.
     */
    suspend fun extractSillyTavern(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("正在解压 SillyTavern...")
        val stDir = File(baseDir, ST_DIR)

        try {
            // Preserve user data across updates
            val dataDir = File(stDir, "data")
            val dataBackup = File(context.cacheDir, "st_data_backup")

            // If backup already exists from a previously interrupted extraction, keep it
            if (dataBackup.exists()) {
                Log.w(TAG, "Found backup from interrupted extraction, will restore after extract")
            } else if (dataDir.exists() && (dataDir.list()?.isNotEmpty() == true)) {
                // Normal case: move data/ to backup before re-extraction
                onProgress("正在备份用户数据...")
                dataDir.renameTo(dataBackup)
                Log.i(TAG, "User data backed up to: ${dataBackup.absolutePath}")
            }
            val hasBackup = dataBackup.exists()

            // Clean old SillyTavern files (data already moved out)
            if (stDir.exists()) {
                onProgress("正在清理旧文件...")
                stDir.deleteRecursively()
            }
            stDir.mkdirs()

            // Copy to temp file first - AssetManager streaming can corrupt large zips
            val tmpZip = File(context.cacheDir, "sillytavern.zip")
            onProgress("正在准备解压文件...")
            context.assets.open("sillytavern.zip").use { input ->
                FileOutputStream(tmpZip).use { output -> input.copyTo(output, 65536) }
            }
            onProgress("正在解压 SillyTavern...")
            FileInputStream(tmpZip).use { input ->
                extractZip(input, stDir, onProgress)
            }
            tmpZip.delete()

            // Restore user data
            if (hasBackup && dataBackup.exists()) {
                onProgress("正在恢复用户数据...")
                if (dataDir.exists()) dataDir.deleteRecursively()
                dataBackup.renameTo(dataDir)
                Log.i(TAG, "User data restored to: ${dataDir.absolutePath}")
            }

            // Write version marker
            File(baseDir, VERSION_FILE).writeText(BUNDLE_VERSION)

            Log.i(TAG, "SillyTavern extracted to: ${stDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SillyTavern", e)
            throw e
        }
    }

    /** Last error message from a failed start attempt */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Ensure SillyTavern config.yaml exists with Android-compatible settings.
     * Most importantly: disable autorun (xdg-open doesn't exist on Android).
     */
    fun ensureConfig() {
        val configFile = File(stDir, "config.yaml")
        // Always overwrite with Android-safe config.
        // Default config has browserLaunch.enabled=true which crashes (no xdg-open).
        Log.i(TAG, "Writing Android config.yaml")
        configFile.writeText("""
            |# Auto-generated for Android — do not edit, will be overwritten on restart
            |dataRoot: ./data
            |listen: false
            |port: $SERVER_PORT
            |protocol:
            |  ipv4: true
            |  ipv6: false
            |dnsPreferIPv6: false
            |autorun: false
            |browserLaunch:
            |  enabled: false
            |  browser: ''
            |securityOverride: false
            |basicAuthMode: false
            |enableCorsProxy: false
            |whitelistMode: false
        """.trimMargin())
    }

    /**
     * Fix corrupted data directory entries from old broken zip extraction.
     * Some directory entries were saved as 0-byte files instead of dirs.
     */
    fun fixCorruptedDataDir() {
        val dataDir = stDataDir
        if (!dataDir.exists()) return

        // Known directories that SillyTavern expects
        val expectedDirs = listOf(
            "_uploads", "thumbnails", "thumbnails/bg", "thumbnails/avatar",
            "characters", "chats", "groups", "group chats", "worlds",
            "User Avatars", "backgrounds", "themes", "presets",
            "context", "instruct", "QuickReplies", "assets",
            "assets/ambient", "assets/bgm", "assets/blip",
            "assets/character", "assets/live2d", "assets/temp", "assets/vrm"
        )

        var fixed = 0
        for (dirName in expectedDirs) {
            val target = File(dataDir, dirName)
            if (target.exists() && !target.isDirectory) {
                Log.w(TAG, "Fixing corrupted entry: ${target.absolutePath} (file -> dir)")
                target.delete()
                target.mkdirs()
                fixed++
            } else if (!target.exists()) {
                target.mkdirs()
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "Fixed $fixed corrupted data entries")
        }
    }

    /**
     * Quick test: run "node --version" to verify the binary works.
     * Returns version string on success, or error message on failure.
     */
    fun testNodeBinary(): String {
        val env = buildEnvironment()
        val pb = ProcessBuilder(nodeBinary.absolutePath, "--version")
            .directory(baseDir)
            .redirectErrorStream(true)
        pb.environment().putAll(env)

        return try {
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Node test OK: $output")
                output
            } else {
                Log.e(TAG, "Node test failed ($exitCode): $output")
                "EXIT $exitCode: $output"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Node test exception", e)
            "ERROR: ${e.message}"
        }
    }

    /**
     * Start the Node.js server process.
     * Returns null on success, or error message on failure.
     */
    fun startServer(): String? {
        if (nodeProcess != null) {
            Log.w(TAG, "Server already running")
            return null
        }

        if (!isReady()) {
            val msg = "Not ready: node=${isNodeInstalled()}, st=${isSillyTavernInstalled()}"
            Log.e(TAG, msg)
            lastError = msg
            return msg
        }

        return try {
            val env = buildEnvironment()
            val cmd = arrayOf(
                nodeBinary.absolutePath,
                "server.js",
                "--port", SERVER_PORT.toString()
            )

            Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")
            Log.i(TAG, "CWD: ${stDir.absolutePath}")
            Log.i(TAG, "ENV: $env")

            val pb = ProcessBuilder(*cmd)
                .directory(stDir)
                .redirectErrorStream(true)

            pb.environment().putAll(env)
            nodeProcess = pb.start()

            // Read initial output to check for immediate failure
            synchronized(processOutput) { processOutput.clear() }
            val reader = nodeProcess!!.inputStream.bufferedReader()

            // Start log reader thread
            Thread {
                try {
                    reader.forEachLine { line ->
                        synchronized(processOutput) {
                            if (processOutput.size < 500) processOutput.add(line)
                        }
                        Log.i("$TAG:Node", line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Log reader stopped: ${e.message}")
                }
            }.apply {
                isDaemon = true
                name = "node-logger"
                start()
            }

            // Wait briefly and check if process died immediately
            Thread.sleep(3000)
            if (!isProcessAlive()) {
                val allLines = synchronized(processOutput) { processOutput.toList() }
                val tail = allLines.takeLast(30).joinToString("\n")
                val msg = "Node.js exited immediately.\n$tail"
                Log.e(TAG, msg)
                nodeProcess = null
                lastError = msg
                return msg
            }

            Log.i(TAG, "Node.js process started and alive")
            lastError = null
            null // success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Node.js", e)
            nodeProcess = null
            lastError = e.message
            "启动异常: ${e.message}"
        }
    }

    /** Stop the Node.js server process */
    fun stopServer() {
        nodeProcess?.let { proc ->
            try {
                proc.destroy()
                proc.waitFor()
                Log.i(TAG, "Node.js process stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Node.js", e)
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
        }
        nodeProcess = null
    }

    /** Get captured process output for diagnostics */
    fun getProcessOutput(): String {
        return synchronized(processOutput) {
            processOutput.takeLast(20).joinToString("\n")
        }
    }

    /** Check if the process is still alive */
    fun isProcessAlive(): Boolean {
        return nodeProcess?.isAlive == true
    }

    /** Check if the HTTP server is responding */
    suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract Node.js shared libraries from assets.
     * These are Termux libraries that node depends on (libz, libssl, libicu, etc.)
     */
    suspend fun extractNodeLibs(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("正在解压 Node.js 依赖库...")
        val libDir = nodeLibsDir
        if (libDir.exists()) libDir.deleteRecursively()
        libDir.mkdirs()

        try {
            // Copy to temp file first - AssetManager streaming can corrupt large zips
            val tmpZip = File(context.cacheDir, "nodelibs.zip")
            context.assets.open("nodelibs.zip").use { input ->
                FileOutputStream(tmpZip).use { output -> input.copyTo(output, 65536) }
            }
            FileInputStream(tmpZip).use { input ->
                extractZip(input, libDir, onProgress)
            }
            tmpZip.delete()
            Log.i(TAG, "Node libs extracted to: ${libDir.absolutePath}, files: ${libDir.list()?.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract node libs", e)
            throw e
        }
    }

    /** Build environment variables for Node.js process */
    private fun buildEnvironment(): Map<String, String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val libsDir = nodeLibsDir.absolutePath
        return mapOf(
            "HOME" to baseDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "NODE_ENV" to "production",
            "PATH" to "$nativeLibDir:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "$libsDir:$nativeLibDir"
        )
    }

    /** Extract a ZIP stream to a target directory */
    private fun extractZip(input: InputStream, targetDir: File, onProgress: (String) -> Unit) {
        var count = 0
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Normalize Windows backslashes to forward slashes for Linux/Android
                val entryName = entry.name.replace('\\', '/')
                val outFile = File(targetDir, entryName)

                // Security: prevent zip slip
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    Log.w(TAG, "Skipping zip entry outside target: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                // Detect directory: original check OR normalized name ends with /
                val isDir = entry.isDirectory || entryName.endsWith("/")

                if (isDir) {
                    outFile.mkdirs()
                } else {
                    // Ensure parent directories exist; remove conflicting files
                    outFile.parentFile?.let { parent ->
                        ensureDirectory(parent)
                    }
                    // If outFile itself is somehow a directory, skip
                    if (outFile.isDirectory) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos, 8192)
                    }
                }

                count++
                if (count % 200 == 0) {
                    onProgress("已解压 $count 个文件...")
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        onProgress("解压完成，共 $count 个文件")
    }

    /** Ensure a path is a directory, removing any conflicting files along the path */
    private fun ensureDirectory(dir: File) {
        if (dir.exists()) {
            if (!dir.isDirectory) {
                dir.delete()
                dir.mkdirs()
            }
        } else {
            // mkdirs may fail if a parent is a file; walk up and fix
            if (!dir.mkdirs() && !dir.isDirectory) {
                // Find the blocking ancestor
                var ancestor: File? = dir
                while (ancestor != null && !ancestor.exists()) {
                    ancestor = ancestor.parentFile
                }
                if (ancestor != null && !ancestor.isDirectory) {
                    ancestor.delete()
                }
                dir.mkdirs()
            }
        }
    }

    /**
     * Import user data from Termux's SillyTavern installation.
     * Source: /data/data/com.termux/files/home/SillyTavern/data/
     * This only works if we have the files copied to shared storage first.
     */
    suspend fun importFromSharedStorage(sourceDir: File, onProgress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            if (!sourceDir.exists()) {
                throw FileNotFoundException("Source directory not found: ${sourceDir.absolutePath}")
            }

            onProgress("正在导入用户数据...")
            val destDir = stDataDir
            destDir.mkdirs()

            sourceDir.walkTopDown().forEach { file ->
                val relativePath = file.relativeTo(sourceDir).path
                val destFile = File(destDir, relativePath)

                if (file.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                }
            }

            onProgress("数据导入完成")
        }
}
