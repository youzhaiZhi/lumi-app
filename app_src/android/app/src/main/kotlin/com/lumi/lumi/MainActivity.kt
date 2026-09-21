package com.lumi.lumi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "a11yStatus" -> result.success(isA11yEnabled())
                    "openA11ySettings" -> {
                        openA11ySettings()
                        result.success(true)
                    }
                    "observe" -> {
                        val service = LumiAccessibilityService.instance
                        if (service == null) result.success(EMPTY_OBSERVE) else result.success(service.observe())
                    }
                    "act" -> {
                        val service = LumiAccessibilityService.instance
                        if (service == null) {
                            result.success("{\"acted\":false,\"error\":\"无障碍服务未开启\"}")
                        } else {
                            result.success(
                                service.act(
                                    call.argument<String>("op") ?: "",
                                    intArg(call, "index"),
                                    call.argument<String>("text")
                                )
                            )
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun intArg(call: MethodCall, key: String): Int =
        (call.argument<Any>(key) as? Number)?.toInt() ?: 0

    /**
     * EnabledServiceList is the reliable read; the AccessibilityManager query is a
     * fallback for ROMs that hide the secure setting.
     */
    private fun isA11yEnabled(): Boolean {
        val expected = "$packageName/${LumiAccessibilityService::class.java.name}"
        val secure = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Exception) {
            null
        }
        if (secure != null && secure.contains(expected, ignoreCase = true)) return true
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val self = LumiAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .any { info ->
                val serviceInfo = info.serviceInfo
                serviceInfo?.packageName == packageName && serviceInfo.name == self
            }
    }

    private fun openA11ySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Some ROMs require the focused-package variant to land on the app list.
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.putExtra(":settings:fragment_args_key", "$packageName/${LumiAccessibilityService::class.java.name}")
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Exception) {
            }
        }
    }

    companion object {
        private const val CHANNEL = "lumi/native"
        private const val EMPTY_OBSERVE = "{\"nodes\":[],\"error\":\"无障碍服务未开启\"}"
    }
}
