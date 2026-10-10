package com.nekoplayer.app

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.mining.AnkiDroidClient
import com.nekoplayer.app.mining.AnkiDroidPermissionRequester

class MainActivity : ComponentActivity(), AnkiDroidPermissionRequester {
    private lateinit var database: AppDatabase
    private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
    private var playerImmersive = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = pendingPermissionCallback
            pendingPermissionCallback = null
            callback?.invoke(granted)
        }

    val ankiClient: AnkiDroidClient by lazy {
        AnkiDroidClient(
            activity = this,
            permissionRequester = this,
            fileProviderAuthority = "$packageName.nekoplayer_media",
        )
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT in (Build.VERSION_CODES.Q until Build.VERSION_CODES.VANILLA_ICE_CREAM)) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        database = AppDatabase(this)
        setContent {
            com.nekoplayer.app.ui.NekoApp(
                activity = this,
                database = database,
                anki = ankiClient,
            )
        }
    }

    override fun request(permission: String, callback: (granted: Boolean) -> Unit) {
        if (pendingPermissionCallback != null) {
            callback(false)
            return
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            callback(true)
            return
        }
        pendingPermissionCallback = callback
        permissionLauncher.launch(permission)
    }

    fun setPlayerImmersive(enabled: Boolean) {
        playerImmersive = enabled
        updateSystemBars()
    }

    fun setSystemBarDarkIcons(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = enabled
        controller.isAppearanceLightNavigationBars = enabled
    }

    private fun updateSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (playerImmersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playerImmersive) window.decorView.post { updateSystemBars() }
    }

    override fun onDestroy() {
        pendingPermissionCallback?.invoke(false)
        pendingPermissionCallback = null
        if (isFinishing) database.close()
        super.onDestroy()
    }
}
