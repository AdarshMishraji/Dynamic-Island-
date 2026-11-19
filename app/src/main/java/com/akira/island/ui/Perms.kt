package com.akira.island.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.akira.island.service.IslandService
import com.akira.island.service.NotifListener

object Perms {
    fun accessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, IslandService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun notifications(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun bluetooth(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Calls: call state is required, the rest is the number, the name and answering with a button. */
    val CALL_PERMISSIONS = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
    }.toTypedArray()

    fun calls(context: Context): Boolean = CALL_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun openAccessibility(context: Context) = start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openNotifications(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, NotifListener::class.java).flattenToString(),
                )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        if (!start(context, intent)) start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Here the "⋮" menu hides "Allow restricted settings" (Android 13+). */
    fun openAppDetails(context: Context) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    /** Battery "Unrestricted": the system doesn't put the island to sleep or unload it. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The "Battery optimization" list; if it isn't there, "App info", where "Battery" is. */
    fun openBattery(context: Context) {
        if (!start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppDetails(context)
    }

    /**
     * Whose phone it is: each firmware has its own autostart and its own pinning in recents.
     * [marks] is how the firmware labels the manufacturer, [autostart] is its "Autostart" screens, from newest to oldest.
     */
    enum class Vendor(val marks: List<String>, val autostart: List<String> = emptyList()) {
        XIAOMI(listOf("xiaomi", "redmi", "poco"), listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        )),
        OPPO(listOf("oppo", "realme", "oneplus"), listOf(
            "com.oplus.safecenter/com.oplus.safecenter.startupapp.view.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
        )),
        VIVO(listOf("vivo", "iqoo"), listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )),
        HUAWEI(listOf("huawei", "honor"), listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
        )),
        SAMSUNG(listOf("samsung")),
        TRANSSION(listOf("infinix", "tecno", "itel", "transsion"), listOf(
            "com.transsion.phonemaster/com.cyin.himgr.autostart.AutoStartActivity",
        )),
        ASUS(listOf("asus"), listOf(
            "com.asus.mobilemanager/com.asus.mobilemanager.autostart.AutoStartActivity",
        )),
        OTHER(emptyList()),
    }

    /** The manufacturer doesn't change, so we determine it once. */
    private val vendor: Vendor by lazy {
        val m = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        Vendor.entries.firstOrNull { v -> v.marks.any { it in m } } ?: Vendor.OTHER
    }

    fun vendor(): Vendor = vendor

    /** Whether this firmware has its own "Autostart". */
    fun hasAutostart(): Boolean = vendor.autostart.isNotEmpty()

    /** Open the firmware's "Autostart"; if that fails, "App info". */
    fun openAutostart(context: Context) {
        val opened = vendor.autostart.any { name ->
            ComponentName.unflattenFromString(name)?.let { start(context, Intent().setComponent(it)) } == true
        }
        if (!opened) openAppDetails(context)
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
