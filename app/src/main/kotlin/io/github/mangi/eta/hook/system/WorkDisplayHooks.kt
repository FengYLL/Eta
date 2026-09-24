package io.github.mangi.eta.hook.system

import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

internal object WorkDisplayHooks {
    @Volatile private var broker: WorkDisplayBroker? = null

    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "WorkDisplay")
        return hooks.install {
            fun type(name: String) = Class.forName(name, false, loader)
            val starter = type("com.android.server.wm.ActivityStarter").declaredMethods.single {
                it.name == "executeRequest" && it.parameterCount == 1
            }
            val container = type("com.android.server.wm.WindowContainer")
            val reparent = container.getDeclaredMethod("reparent", container, Integer.TYPE)
            val atm = type("com.android.server.wm.ActivityTaskManagerService")
            val recents = atm.declaredMethods.single { it.name == "startActivityFromRecents" && it.parameterCount == 2 }
            val front = atm.declaredMethods.single { it.name == "moveTaskToFront" && it.parameterCount == 5 }
            var installed = true
            installed = (hooks.intercept("system.work-display.start", starter, "ActivityStarter.executeRequest") { chain ->
                val b = broker
                val allow = try { b?.guardStart(chain.getArg(0)!!) != false }
                catch (e: Exception) { b?.fault(e); false }
                if (allow) chain.proceed() else -96 // ActivityManager.START_CANCELED
            } != null) && installed
            installed = (hooks.intercept("system.work-display.reparent", reparent, "WindowContainer.reparent") { chain ->
                val b = broker
                val allow = try { b?.guardReparent(chain.getThisObject()!!, chain.getArg(0)!!) != false }
                catch (e: Exception) { b?.fault(e); false }
                if (allow) chain.proceed() else null
            } != null) && installed
            installed = (hooks.intercept("system.work-display.recents", recents, "ActivityTaskManagerService.startActivityFromRecents") { chain ->
                val b = broker
                val allow = try { b?.guardFront(chain.getThisObject()!!, chain.getArg(0) as Int, true) != false }
                catch (e: Exception) { b?.fault(e); false }
                if (allow) chain.proceed() else -96
            } != null) && installed
            installed = (hooks.intercept("system.work-display.front", front, "ActivityTaskManagerService.moveTaskToFront") { chain ->
                val b = broker
                val allow = try { b?.guardFront(chain.getThisObject()!!, chain.getArg(2) as Int, false) != false }
                catch (e: Exception) { b?.fault(e); false }
                if (allow) chain.proceed() else null
            } != null) && installed
            val start = type(ModuleConfig.SYSTEM_SERVER_CLASS).getDeclaredMethod(
                "startOtherServices", type(ModuleConfig.TIMINGS_TRACE_AND_SLOG_CLASS),
            )
            hooks.intercept("system.work-display.service", start, "SystemServer.startOtherServices") { chain ->
                val result = chain.proceed()
                if (broker == null) {
                    val context = SystemServerContextResolver.resolve(chain.getThisObject())
                    if (context != null) {
                        val service = WorkDisplayBroker(context, loader)
                        service.healthy = installed
                        service.start()
                        broker = service
                    }
                }
                result
            }
        }
    }
}
