package com.tungsten.fcl.activity

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mio.JavaManager
import com.mio.manager.RendererManager
import com.mio.util.ImageUtil
import com.tungsten.fcl.R
import com.tungsten.fcl.setting.ConfigHolder
import com.tungsten.fcl.util.AndroidUtils
import com.tungsten.fcl.util.RuntimeUtils
import com.tungsten.fclauncher.utils.Architecture
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.util.Logging
import com.tungsten.fclcore.util.io.FileUtils
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.ui.ProgressDialog
import com.tungsten.fcllibrary.util.LocaleUtils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.Locale
import java.util.logging.Level

@SuppressLint("CustomSplashScreen")
class SplashActivity : FCLActivity() {
    var lwjgl: Boolean = false
    var cacio: Boolean = false
    var cacio17: Boolean = false
    var java8: Boolean = false
    var java17: Boolean = false
    var java21: Boolean = false
    var java25: Boolean = false
    var jna: Boolean = false
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContentView(R.layout.activity_splash)
        sharedPreferences = getSharedPreferences("launcher", MODE_PRIVATE)
        val background = findViewById<ConstraintLayout>(R.id.background)
        ImageUtil.loadInto(
            background,
            ThemeEngine.getInstance().getTheme().getBackground(this)
        )
        if (sharedPreferences.getBoolean("isAgree", false)) {
            checkPermission()
        } else {
            FCLAlertDialog.Builder(this).apply {
                setCancelable(false)
                setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                setMessage(getString(R.string.splash_agreement))
                setPositiveButton {
                    sharedPreferences.edit { putBoolean("isAgree", true) }
                    checkPermission()
                }
                setNegativeButton(getString(com.tungsten.fcllibrary.R.string.crash_reporter_close)) { finish() }
                create().show()
            }
        }
    }

    private fun checkPermission() {
        if (hasPermission()) {
            init()
            return
        }
        FCLAlertDialog.Builder(this).apply {
            setCancelable(false)
            setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            setMessage(getString(R.string.splash_permission_msg))
            setPositiveButton { requestPermission() }
            setNegativeButton { finish() }
            create().show()
        }
    }

    private fun init() {
        lifecycleScope.launch {
            async(Dispatchers.IO) {
                FCLPath.loadPaths(this@SplashActivity)
                Logging.start(Paths.get(FCLPath.LOG_DIR))
                initState()
            }.await()
            if (lwjgl && cacio && cacio17 && java8 && java17 && java21 && java25 && jna) {
                enterLauncher()
            } else {
                autoInstallRuntimes()
            }
        }
    }

    private fun autoInstallRuntimes() {
        val deviceArch = Architecture.archAsString(Architecture.getDeviceArchitecture())
        if (!isJavaArchSupported(deviceArch)) {
            FCLAlertDialog.Builder(this)
                .setMessage(
                    getString(
                        R.string.missing_runtime_arch_files,
                        deviceArch,
                        "PX-release-x.x.x.x-$deviceArch.apk",
                        "PX-release-x.x.x.x-all.apk"
                    )
                )
                .setPositiveButton { }
                .create()
                .show()
            return
        }
        val progress = ProgressDialog(this, getString(R.string.splash_preparing))
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val jobs = mutableListOf<Deferred<Boolean>>()
                if (!lwjgl) jobs.add(async {
                    runCatching {
                        RuntimeUtils.install(this@SplashActivity, FCLPath.LWJGL_DIR, "app_runtime/lwjgl")
                    }.isSuccess
                })
                if (!cacio) jobs.add(async {
                    runCatching {
                        RuntimeUtils.install(this@SplashActivity, FCLPath.CACIOCAVALLO_8_DIR, "app_runtime/caciocavallo")
                    }.isSuccess
                })
                if (!cacio17) jobs.add(async {
                    runCatching {
                        RuntimeUtils.install(this@SplashActivity, FCLPath.CACIOCAVALLO_17_DIR, "app_runtime/caciocavallo17")
                    }.isSuccess
                })
                if (!java8) jobs.add(async {
                    runCatching {
                        RuntimeUtils.installJava(this@SplashActivity, FCLPath.JAVA_8_PATH, "app_runtime/java/jre8")
                    }.isSuccess
                })
                if (!java17) jobs.add(async {
                    runCatching {
                        RuntimeUtils.installJava(this@SplashActivity, FCLPath.JAVA_17_PATH, "app_runtime/java/jre17")
                    }.isSuccess
                })
                if (!java21) jobs.add(async {
                    runCatching {
                        RuntimeUtils.installJava(this@SplashActivity, FCLPath.JAVA_21_PATH, "app_runtime/java/jre21")
                    }.isSuccess
                })
                if (!java25) jobs.add(async {
                    runCatching {
                        RuntimeUtils.installJava(this@SplashActivity, FCLPath.JAVA_25_PATH, "app_runtime/java/jre25")
                    }.isSuccess
                })
                if (!jna) jobs.add(async {
                    runCatching {
                        RuntimeUtils.installJna(this@SplashActivity, FCLPath.JNA_PATH, "app_runtime/jna")
                    }.isSuccess
                })
                jobs.awaitAll()
            }
            withContext(Dispatchers.IO) { initState() }
            progress.dismiss()
            if (lwjgl && cacio && cacio17 && java8 && java17 && java21 && java25 && jna) {
                enterLauncher()
            } else {
                FCLAlertDialog.Builder(this@SplashActivity)
                    .setCancelable(false)
                    .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
                    .setMessage(getString(R.string.splash_runtime_install_failed))
                    .setPositiveButton { autoInstallRuntimes() }
                    .setNegativeButton { finish() }
                    .create()
                    .show()
            }
        }
    }

    private fun isJavaArchSupported(arch: String): Boolean {
        return try {
            val javaDirs = listOf("jre8", "jre17", "jre21", "jre25")
            var supportedCount = 0
            for (javaDir in javaDirs) {
                val dirPath = "app_runtime/java/$javaDir"
                val files = assets.list(dirPath)
                if (files != null && files.contains("bin-$arch.tar.xz")) {
                    supportedCount++
                }
            }
            supportedCount > 0
        } catch (e: Exception) {
            Logging.LOG.log(Level.WARNING, "Failed to check java arch support: ${e.message}")
            false
        }
    }


    fun enterLauncher() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                RendererManager.init(this@SplashActivity)
                JavaManager.init()
                runCatching { ConfigHolder.init() }.exceptionOrNull()?.let {
                    Logging.LOG.log(Level.WARNING, it.message)
                }
            }
            startActivity(
                handleModpack(Intent(this@SplashActivity, MainActivity::class.java)),
                ActivityOptionsCompat.makeCustomAnimation(this@SplashActivity, 0, 0).toBundle()
            )
            finish()
        }
    }

    private fun handleModpack(newIntent: Intent): Intent {
        val intent = intent
        val action = intent.action
        val data = intent.data

        if (Intent.ACTION_VIEW == action && data != null) {
            try {
                val fileName = AndroidUtils.getFileName(this, data) ?: "modpack"
                val cacheFile = File(cacheDir, fileName)
                contentResolver.openInputStream(data)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                newIntent.putExtra("modpack_cache_path", cacheFile.absolutePath)
            } catch (e: Exception) {
                Logging.LOG.log(
                    Level.WARNING,
                    "Failed to handle modpack intent: ${e.message}"
                )
            }
        }
        return newIntent
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:$packageName".toUri()
                    startActivityForResult(this) {
                        checkPermission()
                    }
                }
            } catch (_: Exception) {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) {
                    checkPermission()
                }
            }
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    permission.WRITE_EXTERNAL_STORAGE
                ) || !ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    permission.READ_EXTERNAL_STORAGE
                )
            ) {
                requestPermissions(
                    arrayOf(
                        permission.WRITE_EXTERNAL_STORAGE,
                        permission.READ_EXTERNAL_STORAGE
                    )
                ) {
                    checkPermission()
                }
            } else {
                Toast.makeText(this, R.string.splash_permission_settings_msg, Toast.LENGTH_LONG)
                    .show()
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:$packageName".toUri()
                    startActivityForResult(this) {
                        checkPermission()
                    }
                }
            }
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(
            this,
            permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
            this,
            permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initState() {
        try {
            lwjgl = RuntimeUtils.isLatest(
                FCLPath.LWJGL_DIR,
                "/assets/app_runtime/lwjgl"
            )
            cacio = RuntimeUtils.isLatest(
                FCLPath.CACIOCAVALLO_8_DIR,
                "/assets/app_runtime/caciocavallo"
            )
            cacio17 = RuntimeUtils.isLatest(
                FCLPath.CACIOCAVALLO_17_DIR,
                "/assets/app_runtime/caciocavallo17"
            )
            java8 = RuntimeUtils.isLatest(FCLPath.JAVA_8_PATH, "/assets/app_runtime/java/jre8")
            java17 = RuntimeUtils.isLatest(FCLPath.JAVA_17_PATH, "/assets/app_runtime/java/jre17")
            java21 = RuntimeUtils.isLatest(FCLPath.JAVA_21_PATH, "/assets/app_runtime/java/jre21")
            java25 = RuntimeUtils.isLatest(FCLPath.JAVA_25_PATH, "/assets/app_runtime/java/jre25")
            jna = RuntimeUtils.isLatest(FCLPath.JNA_PATH, "/assets/app_runtime/jna")
            if (!File(FCLPath.JAVA_PATH, "resolv.conf").exists()) {
                if (LocaleUtils.getSystemLocale().displayName != Locale.CHINA.displayName) {
                    FileUtils.writeText(
                        File(FCLPath.JAVA_PATH + "/resolv.conf"), """
     nameserver 1.1.1.1
     nameserver 1.0.0.1
     """.trimIndent()
                    )
                } else {
                    FileUtils.writeText(
                        File(FCLPath.JAVA_PATH + "/resolv.conf"), """
     nameserver 8.8.8.8
     nameserver 8.8.4.4
     """.trimIndent()
                    )
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
