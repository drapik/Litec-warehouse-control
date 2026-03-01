package com.example.hellofly

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var floatingContainer: FrameLayout
    private lateinit var spawnButton: Button
    private lateinit var updateButton: Button

    private lateinit var downloadManager: DownloadManager
    private var downloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId) {
                handleDownloadComplete(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        floatingContainer = findViewById(R.id.floatingContainer)
        spawnButton = findViewById(R.id.spawnButton)
        updateButton = findViewById(R.id.updateButton)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager

        spawnButton.setOnClickListener {
            spawnFlyingText()
        }

        updateButton.setOnClickListener {
            checkForUpdates()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(downloadReceiver)
    }

    private fun spawnFlyingText() {
        val textView = TextView(this).apply {
            text = getString(R.string.hello_world)
            textSize = 24f
            setTextColor(randomBrightColor())
            alpha = 1f
        }

        floatingContainer.addView(
            textView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        floatingContainer.post {
            val maxX = (floatingContainer.width - textView.measuredWidth).coerceAtLeast(0)
            val startX = Random.nextInt(0, maxX + 1).toFloat()
            val startY = (floatingContainer.height - spawnButton.height - dp(24)).toFloat()

            textView.x = startX
            textView.y = startY

            textView.animate()
                .translationY(-floatingContainer.height.toFloat() - dp(120))
                .translationXBy(Random.nextInt(-120, 121).toFloat())
                .alpha(0f)
                .setDuration(1600)
                .withEndAction { floatingContainer.removeView(textView) }
                .start()
        }
    }

    private fun checkForUpdates() {
        updateButton.isEnabled = false
        updateButton.text = "Проверяем..."

        Thread {
            try {
                val latest = fetchLatestReleaseInfo()
                runOnUiThread {
                    if (latest.versionCode > BuildConfig.VERSION_CODE) {
                        Toast.makeText(
                            this,
                            "Найдена версия ${latest.versionCode}. Скачиваем...",
                            Toast.LENGTH_LONG
                        ).show()
                        startApkDownload(latest.apkUrl)
                    } else {
                        updateButton.isEnabled = true
                        updateButton.text = "Обновлений нет"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Ошибка обновления"
                    Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun fetchLatestReleaseInfo(): ReleaseInfo {
        val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("GitHub API вернул HTTP $code")
        }

        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        val json = JSONObject(response)
        val tag = json.getString("tag_name")
        val versionCode = parseVersionCode(tag)

        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name == BuildConfig.APK_ASSET_NAME || name.endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }

        val url = apkUrl ?: throw IllegalStateException("APK asset не найден в релизе")
        return ReleaseInfo(versionCode, url)
    }

    private fun parseVersionCode(tag: String): Int {
        val numeric = tag.trim().removePrefix("v").takeWhile { it.isDigit() }
        return numeric.toIntOrNull() ?: throw IllegalStateException("Неверный tag_name: $tag. Нужен формат v2, v3...")
    }

    private fun startApkDownload(apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("LITEC update")
            setDescription("Скачивание новой версии")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS, BuildConfig.APK_ASSET_NAME)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        downloadId = downloadManager.enqueue(request)
        updateButton.text = "Скачиваем..."
    }

    private fun handleDownloadComplete(id: Long) {
        val query = DownloadManager.Query().setFilterById(id)
        val cursor: Cursor = downloadManager.query(query)
        cursor.use {
            if (!it.moveToFirst()) {
                failUpdate("Не удалось прочитать статус загрузки")
                return
            }
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = it.getInt(statusIndex)
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                failUpdate("Загрузка не завершилась")
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Разреши установку из этого приложения и нажми обновить снова", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            updateButton.isEnabled = true
            updateButton.text = "Нужно разрешение"
            return
        }

        installDownloadedApk()
    }

    private fun installDownloadedApk() {
        val apkFile = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(BuildConfig.APK_ASSET_NAME)
            ?: run {
                failUpdate("APK файл не найден")
                return
            }

        if (!apkFile.exists()) {
            failUpdate("APK файл не найден")
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
            updateButton.isEnabled = true
            updateButton.text = "Установка запущена"
        } catch (e: ActivityNotFoundException) {
            failUpdate("Не найден установщик пакетов")
        }
    }

    private fun failUpdate(message: String) {
        updateButton.isEnabled = true
        updateButton.text = "Проверить обновление"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun randomBrightColor(): Int {
        val r = Random.nextInt(120, 256)
        val g = Random.nextInt(120, 256)
        val b = Random.nextInt(120, 256)
        return Color.rgb(r, g, b)
    }
}

data class ReleaseInfo(
    val versionCode: Int,
    val apkUrl: String
)
