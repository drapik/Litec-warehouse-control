package com.example.hellofly

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var sessionDbHelper: SessionDbHelper
    private val moySkladApi = MoySkladApi()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var loginContainer: LinearLayout
    private lateinit var menuContainer: LinearLayout
    private lateinit var loginEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var loginUpdateButton: Button
    private lateinit var loginProgress: ProgressBar
    private lateinit var loginErrorText: TextView
    private lateinit var sessionInfoText: TextView
    private lateinit var customerOrdersButton: Button
    private lateinit var menuUpdateButton: Button
    private lateinit var logoutButton: Button
    private lateinit var ordersProgress: ProgressBar
    private lateinit var ordersStatusText: TextView
    private lateinit var ordersListView: ListView

    private val orderLines = mutableListOf<String>()
    private lateinit var ordersAdapter: ArrayAdapter<String>

    private var currentSession: StoredSession? = null
    private lateinit var downloadManager: DownloadManager
    private var downloadId: Long = -1L
    private var isUpdateInProgress = false
    private var isDownloadReceiverRegistered = false

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

        sessionDbHelper = SessionDbHelper(this)
        downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        bindViews()
        setupInteractions()

        ordersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, orderLines)
        ordersListView.adapter = ordersAdapter

        currentSession = sessionDbHelper.getSession()
        if (currentSession == null) {
            showLogin()
        } else {
            showMenu(currentSession!!.login)
        }
    }

    override fun onStart() {
        super.onStart()
        registerDownloadReceiverIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        unregisterDownloadReceiverIfNeeded()
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        sessionDbHelper.close()
        super.onDestroy()
    }

    private fun bindViews() {
        loginContainer = findViewById(R.id.loginContainer)
        menuContainer = findViewById(R.id.menuContainer)
        loginEditText = findViewById(R.id.loginEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        loginUpdateButton = findViewById(R.id.loginUpdateButton)
        loginProgress = findViewById(R.id.loginProgress)
        loginErrorText = findViewById(R.id.loginErrorText)
        sessionInfoText = findViewById(R.id.sessionInfoText)
        customerOrdersButton = findViewById(R.id.customerOrdersButton)
        menuUpdateButton = findViewById(R.id.menuUpdateButton)
        logoutButton = findViewById(R.id.logoutButton)
        ordersProgress = findViewById(R.id.ordersProgress)
        ordersStatusText = findViewById(R.id.ordersStatusText)
        ordersListView = findViewById(R.id.ordersListView)
    }

    private fun setupInteractions() {
        loginButton.setOnClickListener { loginToMoySklad() }
        customerOrdersButton.setOnClickListener { loadCustomerOrders() }
        loginUpdateButton.setOnClickListener { checkForUpdates() }
        menuUpdateButton.setOnClickListener { checkForUpdates() }
        logoutButton.setOnClickListener { logout() }
    }

    private fun loginToMoySklad() {
        val login = loginEditText.text.toString().trim()
        val password = passwordEditText.text.toString()

        if (login.isBlank() || password.isBlank()) {
            showLoginError(getString(R.string.login_required))
            return
        }

        setLoginLoading(true)
        ioExecutor.execute {
            try {
                val authHeader = MoySkladApi.buildBasicAuthHeader(login, password)
                moySkladApi.verifyCredentials(authHeader)
                sessionDbHelper.saveSession(login, authHeader)
                val session = StoredSession(login = login, authHeader = authHeader, createdAt = System.currentTimeMillis())

                runOnUiThread {
                    currentSession = session
                    showMenu(login)
                }
            } catch (authException: AuthException) {
                runOnUiThread {
                    setLoginLoading(false)
                    showLoginError(authException.message ?: getString(R.string.auth_failed))
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    setLoginLoading(false)
                    showLoginError(
                        getString(
                            R.string.login_error_template,
                            exception.message ?: getString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }

    private fun loadCustomerOrders() {
        val session = currentSession
        if (session == null) {
            showLogin(getString(R.string.session_expired))
            return
        }

        setOrdersLoading(true)
        ioExecutor.execute {
            try {
                val orders = moySkladApi.fetchAllCustomerOrders(session.authHeader)
                val renderedOrders = orders.mapIndexed { index, order -> renderOrderLine(index, order) }

                runOnUiThread {
                    setOrdersLoading(false)
                    orderLines.clear()
                    orderLines.addAll(renderedOrders)
                    ordersAdapter.notifyDataSetChanged()

                    ordersStatusText.text = if (renderedOrders.isEmpty()) {
                        getString(R.string.orders_empty)
                    } else {
                        getString(R.string.orders_loaded_count, renderedOrders.size)
                    }
                }
            } catch (authException: AuthException) {
                sessionDbHelper.clearSession()
                currentSession = null
                runOnUiThread {
                    setOrdersLoading(false)
                    showLogin(authException.message ?: getString(R.string.session_expired))
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    setOrdersLoading(false)
                    ordersStatusText.text = getString(
                        R.string.orders_load_error_template,
                        exception.message ?: getString(R.string.error_unknown)
                    )
                }
            }
        }
    }

    private fun logout() {
        sessionDbHelper.clearSession()
        currentSession = null
        orderLines.clear()
        ordersAdapter.notifyDataSetChanged()
        showLogin(getString(R.string.logout_done))
    }

    private fun renderOrderLine(index: Int, order: CustomerOrder): String {
        val number = order.name.ifBlank { getString(R.string.order_number_missing) }
        val agent = order.agentName?.takeIf { it.isNotBlank() } ?: getString(R.string.order_agent_missing)
        val moment = order.moment.ifBlank { "-" }
        val amount = String.format(Locale.US, "%.2f", order.sum / 100.0)
        return "${index + 1}. $number | $agent | $moment | $amount"
    }

    private fun checkForUpdates() {
        if (isUpdateInProgress) {
            return
        }

        isUpdateInProgress = true
        setUpdateButtonsEnabled(false)
        setUpdateButtonsText(getString(R.string.update_checking))

        Thread {
            try {
                val latest = fetchLatestReleaseInfo()
                runOnUiThread {
                    if (latest.versionCode > BuildConfig.VERSION_CODE) {
                        Toast.makeText(
                            this,
                            getString(R.string.update_found_template, latest.versionCode),
                            Toast.LENGTH_LONG
                        ).show()
                        startApkDownload(latest.apkUrl)
                    } else {
                        isUpdateInProgress = false
                        setUpdateButtonsEnabled(true)
                        setUpdateButtonsText(getString(R.string.update_no_updates))
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    failUpdate(
                        getString(
                            R.string.update_error_template,
                            exception.message ?: getString(R.string.error_unknown)
                        )
                    )
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

        val resolvedUrl = apkUrl ?: throw IllegalStateException("APK asset не найден в релизе")
        return ReleaseInfo(versionCode = versionCode, apkUrl = resolvedUrl)
    }

    private fun parseVersionCode(tag: String): Int {
        val numeric = tag.trim().removePrefix("v").takeWhile { it.isDigit() }
        return numeric.toIntOrNull()
            ?: throw IllegalStateException("Неверный tag_name: $tag. Ожидается формат v2, v3...")
    }

    private fun startApkDownload(apkUrl: String) {
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("LITEC update")
            setDescription("Скачивание новой версии")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                BuildConfig.APK_ASSET_NAME
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        downloadId = downloadManager.enqueue(request)
        setUpdateButtonsText(getString(R.string.update_downloading))
    }

    private fun handleDownloadComplete(id: Long) {
        val query = DownloadManager.Query().setFilterById(id)
        val cursor: Cursor = downloadManager.query(query)
        cursor.use {
            if (!it.moveToFirst()) {
                failUpdate(getString(R.string.update_download_status_error))
                return
            }
            val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = it.getInt(statusIndex)
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                failUpdate(getString(R.string.update_download_failed))
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.update_permission_toast), Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            isUpdateInProgress = false
            setUpdateButtonsEnabled(true)
            setUpdateButtonsText(getString(R.string.update_permission_needed))
            return
        }

        installDownloadedApk()
    }

    private fun installDownloadedApk() {
        val apkFile = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(BuildConfig.APK_ASSET_NAME)
            ?: run {
                failUpdate(getString(R.string.update_apk_not_found))
                return
            }

        if (!apkFile.exists()) {
            failUpdate(getString(R.string.update_apk_not_found))
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
            isUpdateInProgress = false
            setUpdateButtonsEnabled(true)
            setUpdateButtonsText(getString(R.string.update_install_started))
        } catch (_: ActivityNotFoundException) {
            failUpdate(getString(R.string.update_installer_missing))
        }
    }

    private fun failUpdate(message: String) {
        isUpdateInProgress = false
        setUpdateButtonsEnabled(true)
        setUpdateButtonsText(getString(R.string.update_button))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setUpdateButtonsEnabled(isEnabled: Boolean) {
        loginUpdateButton.isEnabled = isEnabled
        menuUpdateButton.isEnabled = isEnabled
    }

    private fun setUpdateButtonsText(text: String) {
        loginUpdateButton.text = text
        menuUpdateButton.text = text
    }

    private fun registerDownloadReceiverIfNeeded() {
        if (isDownloadReceiverRegistered) {
            return
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
        isDownloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiverIfNeeded() {
        if (!isDownloadReceiverRegistered) {
            return
        }
        unregisterReceiver(downloadReceiver)
        isDownloadReceiverRegistered = false
    }

    private fun setLoginLoading(isLoading: Boolean) {
        loginButton.isEnabled = !isLoading
        loginProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            loginErrorText.visibility = View.GONE
        }
    }

    private fun setOrdersLoading(isLoading: Boolean) {
        customerOrdersButton.isEnabled = !isLoading
        ordersProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isLoading) {
            ordersStatusText.text = getString(R.string.orders_loading)
        }
    }

    private fun showLogin(message: String? = null) {
        loginContainer.visibility = View.VISIBLE
        menuContainer.visibility = View.GONE
        setLoginLoading(false)
        passwordEditText.text = null
        if (!isUpdateInProgress) {
            setUpdateButtonsEnabled(true)
            setUpdateButtonsText(getString(R.string.update_button))
        }

        if (message.isNullOrBlank()) {
            loginErrorText.visibility = View.GONE
        } else {
            showLoginError(message)
        }
    }

    private fun showLoginError(message: String) {
        loginErrorText.text = message
        loginErrorText.visibility = View.VISIBLE
    }

    private fun showMenu(login: String) {
        loginContainer.visibility = View.GONE
        menuContainer.visibility = View.VISIBLE

        sessionInfoText.text = getString(R.string.session_user_template, login)
        setOrdersLoading(false)
        ordersStatusText.text = ""
        orderLines.clear()
        ordersAdapter.notifyDataSetChanged()
        if (!isUpdateInProgress) {
            setUpdateButtonsEnabled(true)
            setUpdateButtonsText(getString(R.string.update_button))
        }
    }
}

data class ReleaseInfo(
    val versionCode: Int,
    val apkUrl: String
)
