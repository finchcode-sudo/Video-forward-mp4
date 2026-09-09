package com.example.videoconverter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 负责在需要时申请运行时权限（主要是 Android 13+ 的通知权限，
 * 用于前台服务进度通知的展示）。
 */
class PermissionHelper(activity: ComponentActivity, private val onResult: (Boolean) -> Unit) {

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onResult(granted) }

    fun requestNotificationPermissionIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onResult(true)
            }
        } else {
            onResult(true)
        }
    }
}
