package com.example.tgproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val version: String,
    val release_url: String,
    val apk_url: String
)

object VersionChecker {
    private const val TAG = "VersionChecker"
    private const val VERSION_URL = "https://raw.githubusercontent.com/Code-in-law/tg-proxy-android/main/version.json"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(context: Context, onComplete: (Boolean) -> Unit = {}) {
        val request = Request.Builder()
            .url(VERSION_URL)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Ошибка проверки обновлений", e)
                onComplete(false)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onComplete(false)
                        return
                    }

                    val json = response.body?.string()
                    if (json.isNullOrEmpty()) {
                        onComplete(false)
                        return
                    }

                    try {
                        val releaseInfo = Gson().fromJson(json, ReleaseInfo::class.java)
                        val currentVersion = context.packageManager
                            .getPackageInfo(context.packageName, 0)
                            .versionName ?: "0.0.0"

                        if (releaseInfo.version.isNotEmpty() && isNewerVersion(releaseInfo.version, currentVersion)) {
                            showUpdateDialog(context, releaseInfo)
                            onComplete(true)
                        } else {
                            Log.d(TAG, "Установлена последняя версия: $currentVersion")
                            onComplete(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка парсинга версии", e)
                        onComplete(false)
                    }
                }
            }
        })
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return try {
            val latestParts = latest.split(".").map { it.toInt() }
            val currentParts = current.split(".").map { it.toInt() }
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val latestPart = latestParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun showUpdateDialog(context: Context, releaseInfo: ReleaseInfo) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Доступно обновление v${releaseInfo.version}")
        builder.setMessage("Нажмите «Обновить», чтобы перейти на страницу загрузки новой версии.")

        builder.setPositiveButton("Обновить") { _, _ ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.release_url))
            context.startActivity(intent)
        }

        builder.setNegativeButton("Позже") { dialog, _ -> dialog.dismiss() }
        builder.setCancelable(true)

        // Показываем диалог в UI-потоке
        if (context is android.app.Activity) {
            (context as android.app.Activity).runOnUiThread {
                builder.show()
            }
        } else {
            builder.show()
        }
    }
}
