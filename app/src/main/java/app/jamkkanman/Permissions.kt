package app.jamkkanman

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.util.Log

/** Requests every declared permission whose protection level is dangerous and not yet granted. */
object Permissions {
    const val REQUEST_CODE = 41

    fun missingDangerous(activity: Activity): List<String> {
        val pm = activity.packageManager
        val info: PackageInfo = pm.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions?.toList().orEmpty()
        return declared.filter { name ->
            val dangerous = try {
                val p = pm.getPermissionInfo(name, 0)
                (p.protection == PermissionInfo.PROTECTION_DANGEROUS)
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            dangerous && activity.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestMissing(activity: Activity) {
        val missing = missingDangerous(activity)
        Log.i("JKM", "dangerous permissions missing: $missing")
        if (missing.isNotEmpty()) activity.requestPermissions(missing.toTypedArray(), REQUEST_CODE)
    }

    /** One line per declared permission: granted or not, and its protection level. */
    fun report(activity: Activity): List<String> {
        val pm = activity.packageManager
        val info = pm.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().map { name ->
            val level = try {
                when (pm.getPermissionInfo(name, 0).protection) {
                    PermissionInfo.PROTECTION_NORMAL -> "normal"
                    PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
                    PermissionInfo.PROTECTION_SIGNATURE -> "signature"
                    else -> "other"
                }
            } catch (e: PackageManager.NameNotFoundException) {
                "정의 없음"
            }
            val granted = activity.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
            "$name [$level] ${if (granted) "허용" else "거부"}"
        }
    }
}
