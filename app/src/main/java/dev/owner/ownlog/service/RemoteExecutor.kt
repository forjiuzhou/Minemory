package dev.owner.ownlog.service

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.owner.ownlog.domain.AppChoice
import dev.owner.ownlog.domain.ControlResult
import dev.owner.ownlog.domain.InstalledApp
import dev.owner.ownlog.domain.PhoneApps
import dev.owner.ownlog.domain.PhoneArgBag
import dev.owner.ownlog.domain.PhoneCommands
import dev.owner.ownlog.domain.PhonePlan
import dev.owner.ownlog.domain.PhonePlanResult
import dev.owner.ownlog.domain.PhoneResults
import dev.owner.ownlog.domain.UiTreeDump
import kotlinx.coroutines.delay

/**
 * Runs one remote command on the accessibility service and always attaches a text UI dump.
 * Password fields stay refused inside [AccessibilityActuator]. OwnLog controls are not clicked.
 */
internal class RemoteExecutor(
    private val service: OwnAccessibilityService,
) {
    private val actuator = AccessibilityActuator(service)

    suspend fun execute(action: String, argsJson: String): String {
        val started = SystemClock.elapsedRealtime()
        val interpreted = PhoneCommands.interpret(action, PhoneArgBag.parse(argsJson))
        val plan = (interpreted as? PhonePlanResult.Ready)?.plan
        if (plan == null) {
            val message = (interpreted as? PhonePlanResult.Invalid)?.message ?: "认不出这条命令"
            return finish(ok = false, message = message, dump = safeDump(), started = started)
        }
        if (plan is PhonePlan.Act && plan.command.backgroundsFirst) {
            val foreground = try {
                actuator.foregroundPackage()
            } catch (_: Exception) {
                null
            }
            if (foreground == service.packageName) {
                service.backgroundSelf()
                delay(450)
            }
        }
        val actionResult = try {
            when (plan) {
                is PhonePlan.Open -> openApp(plan)
                is PhonePlan.Act -> actuator.run(plan.command)
            }
        } catch (error: Exception) {
            Log.w(TAG, "execute $action", error)
            ControlResult.refused("执行失败")
        }
        if (actionResult.ok && plan.action != "dump_ui" && plan.action != "wait") {
            delay(if (plan.action == "open_app") 900 else 400)
        }
        return finish(actionResult.ok, actionResult.message, safeDump(), started)
    }

    private fun openApp(plan: PhonePlan.Open): ControlResult {
        val own = service.packageName
        val target = if (!plan.packageName.isNullOrBlank()) {
            val refused = PhoneApps.rejectPackage(plan.packageName, own)
            if (refused != null) return ControlResult.refused(refused)
            plan.packageName
        } else {
            val installed = try {
                service.packageManager.getInstalledApplications(0).map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = service.packageManager.getApplicationLabel(info)?.toString().orEmpty(),
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
            when (val choice = PhoneApps.choose(plan.label.orEmpty(), installed, own)) {
                is AppChoice.One -> choice.packageName
                is AppChoice.Rejected -> return ControlResult.refused(choice.message)
            }
        }
        val launch = try {
            service.packageManager.getLaunchIntentForPackage(target)
        } catch (_: Exception) {
            null
        } ?: return ControlResult.refused("没找到可启动的应用")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            service.startActivity(launch)
            ControlResult.ok("已打开 $target")
        } catch (_: Exception) {
            ControlResult.refused("系统拒绝打开应用")
        }
    }

    private suspend fun safeDump(): UiTreeDump? {
        return try {
            actuator.describeUi()
        } catch (error: Exception) {
            Log.w(TAG, "dump", error)
            null
        }
    }

    private fun finish(ok: Boolean, message: String, dump: UiTreeDump?, started: Long): String {
        val duration = SystemClock.elapsedRealtime() - started
        return PhoneResults.json(
            ok = ok,
            message = message,
            error = if (ok) null else message,
            foregroundPackage = dump?.foregroundPackage,
            uiTree = dump?.text,
            uiTruncated = dump?.truncated == true,
            durationMs = duration,
        )
    }

    private companion object {
        const val TAG = "OwnLogRemote"
    }
}
