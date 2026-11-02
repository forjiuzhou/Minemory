package dev.owner.ownlog.service

import android.content.Context
import dev.owner.ownlog.domain.ControlCommand
import dev.owner.ownlog.domain.ControlCommands
import dev.owner.ownlog.domain.ControlResult

/**
 * In-process entry for the settings screen and [ControlBinderService].
 * The accessibility service re-checks the 允许操作手机 switch before it acts.
 */
internal object ControlBridge {
    suspend fun executeLine(context: Context, line: String): ControlResult {
        val command = ControlCommands.parse(line) ?: return ControlResult.refused("认不出这条命令")
        return execute(context, command)
    }

    suspend fun execute(context: Context, command: ControlCommand): ControlResult {
        val service = CaptureBridge.accessibilityService()
            ?: return ControlResult.refused("无障碍未连接")
        return service.runControl(command)
    }
}
