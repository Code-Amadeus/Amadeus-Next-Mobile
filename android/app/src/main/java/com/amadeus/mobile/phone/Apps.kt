package com.amadeus.mobile.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object Apps {
    data class App(val packageName: String, val label: String)

    fun list(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * 从无障碍服务的上下文启动：Android 10 起后台启动 Activity 受限，
     * 而绑定中的无障碍服务属于系统豁免的情形。
     */
    fun launch(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw VerbException("bad_package", "no launchable app with package $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }
}
