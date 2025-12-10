package net.pandadev.ziitjetbrains

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.io.HttpRequests
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class Heartbeat(
    val timestamp: String,
    val project: String? = null,
    val language: String? = null,
    val file: String? = null,
    val branch: String? = null,
    val editor: String,
    val os: String
)

@Service
class HeartbeatService : ApplicationActivationListener {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 120000L
        private const val OFFLINE_FILE_NAME = "offline_heartbeats.json"

        private fun getConfigDir(): Path {
            val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")
            return if (xdgConfigHome != null && xdgConfigHome.isNotEmpty()) {
                Paths.get(xdgConfigHome, "ziit")
            } else {
                Paths.get(System.getProperty("user.home"), ".config", "ziit")
            }
        }

        private val CONFIG_DIR = getConfigDir()
        private val OFFLINE_FILE_PATH = CONFIG_DIR.resolve(OFFLINE_FILE_NAME)
        private val LEGACY_OFFLINE_FILE_PATH =
            Paths.get(System.getProperty("user.home"), ".ziit", OFFLINE_FILE_NAME)

        fun getInstance(): HeartbeatService = service()
    }

    private val logger = LogService.Companion.getInstance()
    private val config = Config.Companion.getInstance()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    private var lastHeartbeat: Long = 0
    private var lastFile: String = ""
    private var activeDocumentInfo: Pair<String, String>? = null
    private var heartbeatCount: Int = 0
    private var successCount: Int = 0
    private var failureCount: Int = 0
    private var offlineHeartbeats: MutableList<Heartbeat> = ArrayList()
    private var isOnline: Boolean = true
    private var hasValidApiKey: Boolean = true
    private var lastActivity: Long = System.currentTimeMillis()
    private var todayTotalSeconds: Int = 0
    private var isWindowFocused: Boolean = true
    private var keystrokeTimeout: Int? = null
    private var statusBarWidget: StatusBar.ZiitStatusBarWidget? = null

    init {
        ensureConfigDir()
        migrateOfflineHeartbeats()

        ApplicationManager.getApplication()
            .messageBus
            .connect()
            .subscribe(ApplicationActivationListener.TOPIC, this)

        loadOfflineHeartbeats()
        scheduleHeartbeat()

        scheduler.scheduleAtFixedRate({ syncOfflineHeartbeats() }, 30, 30, TimeUnit.SECONDS)

        scheduler.scheduleAtFixedRate({ fetchDailySummary() }, 0, 15, TimeUnit.MINUTES)

        scheduler.scheduleAtFixedRate({ fetchUserSettings() }, 0, 30, TimeUnit.MINUTES)
    }

    override fun applicationActivated(ideFrame: IdeFrame) {
        logger.log("Application activated (window focused)")
        isWindowFocused = true
        lastActivity = System.currentTimeMillis()
        statusBarWidget?.startTracking()
        fetchDailySummary()
    }

    private fun ensureConfigDir() {
        try {
            Files.createDirectories(CONFIG_DIR)
        } catch (e: Exception) {
            logger.error("Error creating config directory: ${e.message}")
        }
    }

    private fun migrateOfflineHeartbeats() {
        try {
            val legacyFile = LEGACY_OFFLINE_FILE_PATH.toFile()
            val newFile = OFFLINE_FILE_PATH.toFile()

            if (legacyFile.exists() && !newFile.exists()) {
                Files.copy(LEGACY_OFFLINE_FILE_PATH, OFFLINE_FILE_PATH)

                legacyFile.delete()

                logger.info("Migrated offline heartbeats from ${LEGACY_OFFLINE_FILE_PATH} to ${OFFLINE_FILE_PATH}")

                try {
                    val legacyDir = legacyFile.parentFile
                    if (legacyDir.exists() && legacyDir.listFiles()?.isEmpty() == true) {
                        legacyDir.delete()
                        logger.info("Removed empty legacy directory: ${legacyDir.absolutePath}")
                    }
                } catch (e: Exception) {
                    logger.warn("Could not remove empty legacy directory: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error migrating offline heartbeats: ${e.message}")
        }
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        logger.log("Application deactivated (window lost focus)")
        isWindowFocused = false
        statusBarWidget?.stopTracking()
    }

    fun setStatusBarWidget(widget: StatusBar.ZiitStatusBarWidget) {
        this.statusBarWidget = widget
        this.statusBarWidget?.setOnlineStatus(isOnline)
        this.statusBarWidget?.setApiKeyStatus(hasValidApiKey)
    }

    fun handleFileChange(file: VirtualFile) {
        val fileName = file.name

        val fileType =
            FileTypeManager.getInstance().getFileTypeByFile(file)
        val language =
            when {
                fileType.name != "UNKNOWN" -> fileType.name.lowercase()
                file.extension != null -> file.extension?.lowercase() ?: "unknown"
                else -> "unknown"
            }

        logger.log("File changed: $fileName ($language)")
        activeDocumentInfo = Pair(fileName, language)
        updateActivity()
        sendHeartbeat(true)
    }

    private fun getFileNameFromEditor(editor: Editor): String? {
        val document = editor.document
        val fileDocManager = FileDocumentManager.getInstance()
        val file = fileDocManager.getFile(document)
        return file?.name
    }

    private fun getLanguageFromEditor(editor: Editor): String? {
        val document = editor.document
        val fileDocManager = FileDocumentManager.getInstance()
        val file = fileDocManager.getFile(document)

        if (file != null) {
            val fileType =
                FileTypeManager
                    .getInstance()
                    .getFileTypeByFile(file)
            return when {
                fileType.name != "UNKNOWN" -> fileType.name.lowercase()
                file.extension != null -> file.extension?.lowercase() ?: "unknown"
                else -> "unknown"
            }
        }

        return "unknown"
    }

    private fun getProjectName(file: VirtualFile?): String? {
        if (file == null) return null

        try {
            val projectManager = ProjectManager.getInstance()
            for (project in projectManager.openProjects) {
                val projectBasePath = project.basePath
                if (projectBasePath != null && file.path.startsWith(projectBasePath)) {
                    return project.name
                }
            }

            val projectDir = file.parent
            if (projectDir != null) {
                return projectDir.name
            }
        } catch (e: Exception) {
            logger.error("Error getting project name: ${e.message}")
        }

        return null
    }

    private fun getGitBranch(file: VirtualFile?): String? {
        if (file == null) return null

        try {
            val projectManager = ProjectManager.getInstance()
            for (project in projectManager.openProjects) {
                val vcsManager =
                    ProjectLevelVcsManager.getInstance(project)
                val gitVcs =
                    vcsManager.allActiveVcss.find { it.name.equals("Git", ignoreCase = true) }

                if (gitVcs != null) {
                    val gitPath = file.path
                    for (root in vcsManager.allVersionedRoots) {
                        if (gitPath.startsWith(root.path)) {
                            val process =
                                Runtime.getRuntime()
                                    .exec(
                                        arrayOf(
                                            "git",
                                            "rev-parse",
                                            "--abbrev-ref",
                                            "HEAD"
                                        ),
                                        null,
                                        File(root.path)
                                    )
                            val reader = process.inputStream.bufferedReader()
                            val branch = reader.readLine()
                            reader.close()
                            process.waitFor()

                            if (!branch.isNullOrEmpty()) {
                                return branch
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error getting Git branch: ${e.message}")
        }

        return null
    }

    private fun updateActivity() {
        lastActivity = System.currentTimeMillis()
        statusBarWidget?.startTracking()
    }

    private fun scheduleHeartbeat() {
        logger.log("Setting up heartbeat schedule with interval: $HEARTBEAT_INTERVAL_MS ms")

        scheduler.scheduleAtFixedRate(
            {
                if (activeDocumentInfo != null && isUserActive()) {
                    sendHeartbeat()
                } else {
                    logger.log("User inactive or no active document, skipping heartbeat")
                    if (!isUserActive()) {
                        statusBarWidget?.stopTracking()
                    }
                }
            },
            HEARTBEAT_INTERVAL_MS,
            HEARTBEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun isUserActive(): Boolean {
        val now = System.currentTimeMillis()
        val keystrokeTimeoutMs = keystrokeTimeout?.let { it * 60 * 1000 }

        return if (keystrokeTimeoutMs != null) {
            isWindowFocused && now - lastActivity < keystrokeTimeoutMs
        } else {
            isWindowFocused
        }
    }

    fun updateKeystrokeTimeout(timeoutMinutes: Int?) {
        this.keystrokeTimeout = timeoutMinutes
        logger.log("Keystroke timeout updated to ${timeoutMinutes ?: "disabled"}")
    }

    private fun fetchDailySummary() {
        val apiKey = config.getApiKey()
        val baseUrl = config.getBaseUrl()

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cal = Calendar.getInstance()
                val currentOffsetMs = cal.timeZone.getOffset(System.currentTimeMillis())
                val timezoneOffsetSeconds = (currentOffsetMs / 1000)

                val url =
                    URI(
                        "$baseUrl/api/external/stats" +
                                "?timeRange=today" +
                                "&midnightOffsetSeconds=$timezoneOffsetSeconds" +
                                "&t=${System.currentTimeMillis()}"
                    )

                val response =
                    HttpRequests.request(url.toString())
                        .tuner { connection ->
                            connection.setRequestProperty("Authorization", "Bearer $apiKey")
                        }
                        .readString()

                setOnlineStatus(true)
                setApiKeyStatus(true)

                val jsonResponse = JSONObject(response)
                val summariesArray = jsonResponse.getJSONArray("summaries")

                if (summariesArray.length() > 0) {
                    val todaySummary = summariesArray.getJSONObject(0)
                    todayTotalSeconds = todaySummary.getInt("totalSeconds")

                    val hours = todayTotalSeconds / 3600
                    val minutes = (todayTotalSeconds % 3600) / 60
                    statusBarWidget?.updateTime(hours, minutes)
                } else {
                    todayTotalSeconds = 0
                    statusBarWidget?.updateTime(0, 0)
                }
            } catch (e: Exception) {
                if (e.message?.contains("401") == true) {
                    setApiKeyStatus(false)
                    logger.error("Error fetching daily summary: Invalid API key")
                } else {
                    setOnlineStatus(false)
                    logger.error("Error fetching daily summary: ${e.message}")
                }
            }
        }
    }

    fun fetchUserSettings() {
        val apiKey = config.getApiKey()
        val baseUrl = config.getBaseUrl()

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) {
            logger.log("Can't fetch user settings: missing API key or base URL")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val url = URI("$baseUrl/api/external/user")

                HttpRequests.request(url.toString())
                    .tuner { connection ->
                        connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    .readString()

                setApiKeyStatus(true)
            } catch (e: Exception) {
                if (e.message?.contains("401") == true) {
                    setApiKeyStatus(false)
                    logger.error("Invalid API key detected when fetching user settings")
                } else {
                    setOnlineStatus(false)
                    logger.error("Failed to fetch user settings: ${e.message}")
                }
            }
        }
    }

    private fun heartbeatToJson(heartbeat: Heartbeat): JSONObject {
        return JSONObject().apply {
            put("timestamp", heartbeat.timestamp)
            heartbeat.project?.let { put("project", it) }
            heartbeat.language?.let { put("language", it) }
            heartbeat.file?.let { put("file", it) }
            heartbeat.branch?.let { put("branch", it) }
            put("editor", heartbeat.editor)
            put("os", heartbeat.os)
        }
    }

    private fun sendJsonRequest(url: URI, jsonData: String, apiKey: String): String {
        return HttpRequests.post(url.toString(), "application/json")
            .tuner { connection ->
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
            }
            .productNameAsUserAgent()
            .gzip(true)
            .connect { request ->
                request.write(jsonData)
                request.readString()
            }
    }

    private fun syncOfflineHeartbeats() {
        if (!isOnline || offlineHeartbeats.isEmpty()) return

        val apiKey = config.getApiKey()
        val baseUrl = config.getBaseUrl()

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) {
            return
        }

        val batch = ArrayList(offlineHeartbeats)
        offlineHeartbeats.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val url = URI("$baseUrl/api/external/batch")
                val jsonArray = JSONArray()
                batch.forEach { heartbeat ->
                    jsonArray.put(heartbeatToJson(heartbeat))
                }

                sendJsonRequest(url, jsonArray.toString(), apiKey)

                this.setOnlineStatus(true)
                this.setApiKeyStatus(true)
                this.saveOfflineHeartbeats()
                this.fetchDailySummary()
                logger.log("Successfully synced ${batch.size} offline heartbeats")
            } catch (e: Exception) {
                if (e.message?.contains("401") == true) {
                    this.setApiKeyStatus(false)
                    logger.error("Invalid API key detected when syncing offline heartbeats")
                } else {
                    this.setOnlineStatus(false)
                    logger.error("Error syncing offline heartbeats: ${e.message}")

                    synchronized(offlineHeartbeats) {
                        offlineHeartbeats.addAll(batch)
                        this.saveOfflineHeartbeats()
                    }
                }
            }
        }
    }

    private fun sendHeartbeat(force: Boolean = false) {
        if (activeDocumentInfo == null) return

        val now = System.currentTimeMillis()
        val fileChanged = lastFile != activeDocumentInfo!!.first
        val timeThresholdPassed = now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS

        if (!force && !fileChanged && !timeThresholdPassed) {
            return
        }

        lastFile = activeDocumentInfo!!.first
        lastHeartbeat = now
        heartbeatCount++

        val (fileName, language) = activeDocumentInfo!!

        val fileDocManager = FileDocumentManager.getInstance()
        val docFile =
            EditorFactory.getInstance()
                .allEditors
                .firstOrNull { editor ->
                    fileDocManager.getFile(editor.document)?.name == fileName
                }
                ?.let { editor -> fileDocManager.getFile(editor.document) }

        val project = getProjectName(docFile) ?: "Unknown"
        val apiKey = config.getApiKey()
        val baseUrl = config.getBaseUrl()

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) {
            return
        }

        val branch = getGitBranch(docFile)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val utcTimestamp = sdf.format(Date())

        val heartbeat =
            Heartbeat(
                timestamp = utcTimestamp,
                project = project,
                language = language,
                file = fileName,
                branch = branch,
                editor =
                    if (ApplicationInfo.getInstance()
                            .fullApplicationName
                            .contains("Ultimate")
                    )
                        "IntelliJ Ultimate"
                    else "IntelliJ Community Edition",
                os =
                    System.getProperty("os.name").let {
                        when {
                            it.lowercase().contains("windows") -> "Windows"
                            it.lowercase().contains("mac") -> "macOS"
                            else -> "Linux"
                        }
                    }
            )

        if (!isOnline) {
            synchronized(offlineHeartbeats) {
                offlineHeartbeats.add(heartbeat)
                this.saveOfflineHeartbeats()
            }
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val url = URI("$baseUrl/api/external/heartbeat")
                val jsonData = heartbeatToJson(heartbeat)

                sendJsonRequest(url, jsonData.toString(), apiKey)

                successCount++
                this.setOnlineStatus(true)
                this.setApiKeyStatus(true)
            } catch (e: Exception) {
                failureCount++
                if (e.message?.contains("401") == true) {
                    this.setApiKeyStatus(false)
                    logger.error("Invalid API key detected when sending heartbeat")
                } else {
                    this.setOnlineStatus(false)
                    logger.error("Failed to send heartbeat: ${e.message}")
                    synchronized(offlineHeartbeats) {
                        offlineHeartbeats.add(heartbeat)
                        this.saveOfflineHeartbeats()
                    }
                }
            }
        }
    }

    private fun loadOfflineHeartbeats() {
        try {
            val file = File(OFFLINE_FILE_PATH.toString())
            if (file.exists()) {
                val json = String(Files.readAllBytes(OFFLINE_FILE_PATH), StandardCharsets.UTF_8)
                val jsonArray = JSONArray(json)

                synchronized(offlineHeartbeats) {
                    offlineHeartbeats.clear()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val heartbeat =
                            Heartbeat(
                                timestamp = obj.getString("timestamp"),
                                project =
                                    if (obj.has("project")) obj.getString("project")
                                    else null,
                                language =
                                    if (obj.has("language")) obj.getString("language")
                                    else null,
                                file = if (obj.has("file")) obj.getString("file") else null,
                                branch =
                                    if (obj.has("branch")) obj.getString("branch")
                                    else null,
                                editor = obj.getString("editor"),
                                os = obj.getString("os")
                            )
                        offlineHeartbeats.add(heartbeat)
                    }
                }
                logger.log("Loaded ${offlineHeartbeats.size} offline heartbeats")
            }
        } catch (e: Exception) {
            logger.error("Error loading offline heartbeats: ${e.message}")
            synchronized(offlineHeartbeats) { offlineHeartbeats.clear() }
        }
    }

    private fun saveOfflineHeartbeats() {
        try {
            val snapshot: List<Heartbeat>
            synchronized(offlineHeartbeats) { snapshot = ArrayList(offlineHeartbeats) }

            val jsonArray = JSONArray()
            for (heartbeat in snapshot) {
                jsonArray.put(heartbeatToJson(heartbeat))
            }

            Files.write(OFFLINE_FILE_PATH, jsonArray.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("Error saving offline heartbeats: ${e.message}")
        }
    }

    private fun setOnlineStatus(isOnline: Boolean) {
        if (this.isOnline != isOnline) {
            this.isOnline = isOnline
            ApplicationManager.getApplication().invokeLater {
                statusBarWidget?.setOnlineStatus(isOnline)
            }
            logger.log("Online status changed to: ${if (isOnline) "online" else "offline"}")
        }
    }

    private fun setApiKeyStatus(isValid: Boolean) {
        if (this.hasValidApiKey != isValid) {
            this.hasValidApiKey = isValid
            ApplicationManager.getApplication().invokeLater {
                statusBarWidget?.setApiKeyStatus(isValid)
            }
            logger.log("API key status changed to: ${if (isValid) "valid" else "invalid"}")
        }
    }

    fun dispose() {
        scheduler.shutdown()
        saveOfflineHeartbeats()
    }
}
