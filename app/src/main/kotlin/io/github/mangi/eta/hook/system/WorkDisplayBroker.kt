package io.github.mangi.eta.hook.system

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.os.UserHandle
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.mangi.eta.agent.display.DisplayLease
import io.github.mangi.eta.agent.display.DisplayProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lives in system_server. Owns one bounded offscreen surface, never encodes images or runs model code.
 * The app obtains this Binder through a signature-protected, sender-UID-checked ordered broadcast.
 * Every transaction independently checks the actual calling UID and the session generation.
 */
internal class WorkDisplayBroker(private val context: Context, private val loader: ClassLoader) {
    private val worker = Handler(HandlerThread("eta-work-display").apply { start() }.looper)
    private val actionLock = Any()
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val authorized = ThreadLocal<Session?>()
    @Volatile var healthy = false
    private val atm by lazy {
        DisplayReflection.call(Class.forName("android.app.ActivityTaskManager"), "getService")!!
    }
    private val input by lazy { context.getSystemService("input") }

    internal class Session(
        val uid: Int,
        val client: IBinder,
        val display: VirtualDisplay,
        val reader: ImageReader,
    ) {
        val id = UUID.randomUUID().toString()
        val userId = uid / 100000
        val displayId = display.display.displayId
        val lease = DisplayLease()
        val packages = ConcurrentHashMap.newKeySet<String>()
        val taskIds = ConcurrentHashMap.newKeySet<Int>()
        @Volatile var reason = ""
        var death: IBinder.DeathRecipient? = null
        // Guarded by actionLock; revoked sessions never replay these operation IDs.
        val operations = LinkedHashSet<String>()
    }

    fun start() {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val uid = sentFromUid
                if (intent.getIntExtra("version", 0) != DisplayProtocol.VERSION || !isOwner(uid)) return
                resultExtras = Bundle().apply { putBinder("broker", endpoint); putInt("version", DisplayProtocol.VERSION) }
                resultCode = 1
            }
        }, IntentFilter(DisplayProtocol.ACTION), DisplayProtocol.PERMISSION, worker, Context.RECEIVER_EXPORTED)
    }

    private fun isOwner(uid: Int): Boolean = uid >= 10000 &&
        context.packageManager.getPackagesForUid(uid)?.contains(DisplayProtocol.PACKAGE) == true &&
        context.checkPermission(DisplayProtocol.PERMISSION, -1, uid) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != DisplayProtocol.TRANSACT || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DisplayProtocol.DESCRIPTOR)
            val uid = getCallingUid()
            if (!isOwner(uid)) throw SecurityException("Not ETA")
            val request = data.readBundle(javaClass.classLoader) ?: Bundle.EMPTY
            val identity = clearCallingIdentity()
            val result = try {
                check(request.getInt("version") == DisplayProtocol.VERSION) { "显示协议版本不匹配，请重启手机" }
                dispatch(uid, request).apply { putBoolean("ok", true) }
            } catch (failure: Exception) {
                Bundle().apply {
                    putBoolean("ok", false)
                    putString("message", failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName)
                }
            } finally { restoreCallingIdentity(identity) }
            reply.writeNoException()
            reply.writeBundle(result)
            return true
        }
    }

    private fun dispatch(uid: Int, request: Bundle): Bundle {
        val operation = request.getString("op")
        if (operation == "health") return Bundle().apply { putBoolean("healthy", healthy) }
        if (operation == "create") return synchronized(actionLock) { create(uid, request) }
        val session = sessions[uid] ?: error("工作屏不存在，请重新创建；旧动作不会重放")
        check(session.id == request.getString("session")) { "工作屏会话已更换，旧请求已拒绝" }
        // Revocation is deliberately independent of actionLock (a gesture may hold it).
        when (operation) {
            "pause" -> {
                if (!request.containsKey("run") || request.getString("run") == session.lease.runId) session.lease.pause()
                return status(session)
            }
            "retain" -> { session.lease.retain(request.getString("run").orEmpty()); return status(session) }
            "status" -> return status(session)
        }
        return synchronized(actionLock) {
            check(sessions[uid] === session) { "工作屏已关闭" }
            when (operation) {
                "acquire" -> {
                    validateEnvironment(session)
                    session.lease.acquire(request.getString("run").orEmpty())
                    session.reason = ""
                    status(session)
                }
                "close" -> { destroy(session); Bundle() }
                "takeover" -> { takeover(session); status(session) }
                "launch" -> {
                    requireAction(session, request)
                    val intent = request.getParcelable("intent", Intent::class.java) ?: error("缺少启动 Intent")
                    launch(session, intent)
                    status(session)
                }
                "touch" -> {
                    requireAction(session, request)
                    touch(session, request)
                    status(session)
                }
                "back" -> {
                    requireAction(session, request)
                    val now = SystemClock.uptimeMillis()
                    inject(session, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
                    // Release only the key already submitted, even if pause arrived meanwhile.
                    inject(session, KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
                    status(session)
                }
                "validate" -> { requireAction(session, request, remember = false); status(session) }
                else -> error("不支持的工作屏操作")
            }
        }
    }

    private fun create(uid: Int, request: Bundle): Bundle {
        check(healthy) { "工作屏路由守卫未完整安装，请启用 LSPosed 系统框架作用域并重启；当前 ROM 也可能不兼容" }
        sessions[uid]?.let { return status(it) }
        check(sessions.isEmpty()) { "另一个 Android 用户仍持有工作屏，请先关闭" }
        val currentUser = DisplayReflection.call(ActivityManager::class.java, "getCurrentUser") as Int
        check(uid / 100000 == currentUser) { "只允许当前 Android 用户创建工作屏" }
        val client = request.getBinder("client") ?: error("缺少生命周期令牌")
        check(client.isBinderAlive) { "客户端已断开" }
        val reader = ImageReader.newInstance(DisplayProtocol.WIDTH, DisplayProtocol.HEIGHT, PixelFormat.RGBA_8888, 3)
        var display: VirtualDisplay? = null
        try {
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, worker)
            display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "Eta work display", DisplayProtocol.WIDTH, DisplayProtocol.HEIGHT, DisplayProtocol.DENSITY,
                reader.surface, DisplayProtocol.CREATE_FLAGS,
            ) ?: error("系统未创建工作屏")
            check(display.display.displayId > 0) { "不能使用主屏" }
            check(display.display.flags and DisplayProtocol.REQUIRED_DISPLAY_FLAGS == DisplayProtocol.REQUIRED_DISPLAY_FLAGS) {
                "ROM 未保留独立焦点/禁止抢焦点标志，拒绝运行"
            }
            val session = Session(uid, client, display, reader)
            val death = IBinder.DeathRecipient {
                session.lease.lost()
                worker.post { synchronized(actionLock) { if (sessions[uid] === session) destroy(session) } }
            }
            session.death = death
            client.linkToDeath(death, 0)
            sessions[uid] = session
            if (!client.isBinderAlive) { destroy(session); error("客户端在创建期间断开") }
            return status(session)
        } catch (failure: Throwable) {
            display?.release()
            reader.close()
            throw failure
        }
    }

    private fun validateEnvironment(s: Session) {
        check(healthy) { "工作屏守卫失效" }
        check(DisplayReflection.call(ActivityManager::class.java, "getCurrentUser") == s.userId) { "Android 用户已切换" }
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁定，请解锁后继续" }
        check(s.display.display.isValid) { "工作屏已被系统移除" }
    }

    private fun requireAction(s: Session, r: Bundle, remember: Boolean = true) {
        validateEnvironment(s)
        check(s.lease.accepts(r.getString("run").orEmpty(), r.getLong("epoch", -1))) { "工作屏已暂停、接管或观察已过期" }
        if (remember) {
            val operation = r.getString("operation_id").orEmpty()
            require(operation.isNotBlank())
            check(s.operations.add(operation)) { "动作已提交过，不能重放；请重新观察" }
            if (s.operations.size > 2048) s.operations.remove(s.operations.first())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tasks(): List<ActivityManager.RunningTaskInfo> =
        DisplayReflection.call(atm, "getTasks", 100, false, false, -1) as List<ActivityManager.RunningTaskInfo>

    private fun launch(s: Session, raw: Intent) = internally(s) {
        // No file grants, selectors or caller-supplied display/task options cross this boundary.
        val intent = Intent(raw.action, raw.data).apply {
            component = raw.component
            `package` = raw.`package`
            raw.categories?.forEach(::addCategory)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val userContext = DisplayReflection.call(context, "createContextAsUser", user(s.userId), 0) as Context
        val info = userContext.packageManager.resolveActivity(intent, 0)?.activityInfo ?: error("没有可启动的应用")
        check(info.packageName != DisplayProtocol.PACKAGE) { "Eta 自身不能作为工作屏目标" }
        check(info.exported) { "目标 Activity 不允许外部启动" }
        val targetPackage = info.packageName
        // Claim before launch so concurrent external starts cannot create a second task on display 0.
        s.packages.add(targetPackage)
        tasks().filter { taskUser(it) == s.userId && (it.baseActivity?.packageName == targetPackage || it.topActivity?.packageName == targetPackage) }
            .forEach { task ->
                if (task.displayId != s.displayId) migrate(s, task, s.displayId)
                s.taskIds.add(task.taskId)
            }
        intent.component = android.content.ComponentName(info.packageName, info.name)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(s.displayId).toBundle()
        DisplayReflection.call(context, "startActivityAsUser", intent, options, user(s.userId))
        tasks().filter { taskUser(it) == s.userId && it.displayId == s.displayId }.forEach { s.taskIds.add(it.taskId) }
    }

    private fun migrate(s: Session, info: ActivityManager.RunningTaskInfo, target: Int) {
        check(taskUser(info) == s.userId)
        val lock = DisplayReflection.get(atm, "mGlobalLock")!!
        synchronized(lock) {
            val root = DisplayReflection.get(atm, "mRootWindowContainer")!!
            val task = DisplayReflection.call(root, "anyTaskForId", info.taskId) ?: error("任务已消失")
            check(DisplayReflection.call(task, "getRootTask") === task && DisplayReflection.call(task, "isLeafTask") == true &&
                DisplayReflection.call(task, "getWindowingMode") == 1) { "分屏、画中画或嵌套任务不能安全迁移，请先退出这些模式" }
            check(info.baseActivity?.packageName == info.topActivity?.packageName) { "混合应用任务需手动整理后再迁移" }
            DisplayReflection.call(atm, "moveRootTaskToDisplay", info.taskId, target)
        }
    }

    private fun takeover(s: Session) = internally(s) {
        s.lease.takeover()
        tasks().filter { taskUser(it) == s.userId && it.displayId == s.displayId }.forEach { migrate(s, it, 0) }
        s.reason = "已在主屏接管；关闭工作屏后可创建新会话"
    }

    private fun touch(s: Session, r: Bundle) {
        val x1 = r.getInt("x1"); val y1 = r.getInt("y1")
        val x2 = r.getInt("x2", x1); val y2 = r.getInt("y2", y1)
        require(listOf(x1, x2).all { it in 0 until DisplayProtocol.WIDTH } &&
            listOf(y1, y2).all { it in 0 until DisplayProtocol.HEIGHT }) { "坐标越界" }
        val duration = r.getInt("duration", 60).coerceIn(40, 3000)
        val down = SystemClock.uptimeMillis()
        var x = x1.toFloat(); var y = y1.toFloat(); var ended = false
        fun event(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; inject(s, event) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN)
        try {
            val steps = (duration / 16).coerceAtLeast(1)
            for (step in 1..steps) {
                SystemClock.sleep((duration / steps).toLong())
                requireAction(s, r, remember = false)
                x = x1 + (x2 - x1) * step.toFloat() / steps
                y = y1 + (y2 - y1) * step.toFloat() / steps
                if (step < steps) event(MotionEvent.ACTION_MOVE)
            }
            event(MotionEvent.ACTION_UP)
            ended = true
        } finally {
            if (!ended) runCatching { event(MotionEvent.ACTION_CANCEL) }
        }
    }

    private fun inject(s: Session, event: InputEvent) {
        DisplayReflection.call(event, "setDisplayId", s.displayId)
        check(DisplayReflection.call(input!!, "injectInputEvent", event, 0) == true) { "定向输入被系统拒绝；结果不明，请重新观察" }
    }

    private fun destroy(s: Session) {
        s.lease.close()
        // DESTROY_CONTENT_ON_REMOVAL prevents Android migrating pages to the main display.
        internally(s) { s.display.release() }
        s.reader.close()
        s.death?.let { runCatching { s.client.unlinkToDeath(it, 0) } }
        sessions.remove(s.uid, s)
    }

    private fun status(s: Session) = Bundle().apply {
        putString("session", s.id); putInt("display", s.displayId); putInt("user", s.userId)
        putLong("epoch", s.lease.epoch); putString("state", s.lease.state.name)
        putString("reason", s.reason); putString("run", s.lease.runId)
        putInt("width", DisplayProtocol.WIDTH); putInt("height", DisplayProtocol.HEIGHT)
    }

    private fun user(id: Int): UserHandle = DisplayReflection.call(UserHandle::class.java, "of", id) as UserHandle
    private fun taskUser(task: ActivityManager.RunningTaskInfo): Int = DisplayReflection.get(task, "userId") as Int
    private fun displayOf(container: Any): Int = DisplayReflection.call(container, "getDisplayContent")?.let {
        DisplayReflection.call(it, "getDisplayId") as Int
    } ?: -1
    private fun <T> internally(s: Session, block: () -> T): T {
        val previous = authorized.get(); authorized.set(s)
        return try { block() } finally { authorized.set(previous) }
    }

    /** Called under framework task locks. No IPC, disk or waits in any guard below. */
    fun guardStart(request: Any): Boolean {
        if (sessions.isEmpty()) return true
        val info = DisplayReflection.get(request, "activityInfo") as? ActivityInfo ?: return true
        val targetUser = info.applicationInfo.uid / 100000
        val source = (DisplayReflection.get(request, "resultTo") as? IBinder)?.let {
            DisplayReflection.call(Class.forName("com.android.server.wm.ActivityRecord", false, loader), "forTokenLocked", it)
        }
        val sourceDisplay = source?.let(::displayOf)
        val s = authorized.get() ?: sessions.values.firstOrNull {
            it.userId == targetUser && (info.packageName in it.packages || sourceDisplay == it.displayId)
        } ?: return true
        if (s.lease.state == DisplayLease.State.HUMAN || s.lease.state == DisplayLease.State.CLOSED) return true
        val own = authorized.get() === s || sourceDisplay == s.displayId
        if (!own) {
            // A launcher-origin MAIN/LAUNCHER start is an explicit takeover after completion.
            val intent = DisplayReflection.get(request, "intent") as? Intent
            if (s.lease.state == DisplayLease.State.RETAINED && intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                // Migration itself stays in the normal launcher path, now allowed by the state.
                s.lease.takeover()
                s.reason = "用户从主屏打开应用，已接管"
                return true
            }
            s.reason = "ETA 正在占用应用，请在工作屏中暂停或接管"
            return false
        }
        if (!s.lease.exclusive) { s.reason = "结果页请求启动新页面，请主动接管"; return false }
        check(targetUser == s.userId) { "禁止跨 Android 用户启动" }
        s.packages.add(info.packageName)
        val safe = DisplayReflection.get(request, "activityOptions")
        val options = (safe?.let { DisplayReflection.call(it, "getOriginalOptions") } as? ActivityOptions)
            ?: ActivityOptions.makeBasic()
        options.launchDisplayId = s.displayId
        if (safe == null) {
            val safeClass = Class.forName("com.android.server.wm.SafeActivityOptions", false, loader)
            DisplayReflection.set(request, "activityOptions", safeClass.getConstructor(ActivityOptions::class.java).newInstance(options))
        } else DisplayReflection.set(safe, "mOriginalOptions", options)
        return true
    }

    fun guardReparent(container: Any, parent: Any): Boolean {
        if (sessions.isEmpty()) return true
        val from = displayOf(container)
        val to = displayOf(parent)
        if (from == to || from < 0 || to < 0) return true
        val s = sessions.values.firstOrNull { it.displayId == from } ?: return true
        return authorized.get() === s || s.lease.state in setOf(DisplayLease.State.HUMAN, DisplayLease.State.CLOSED)
    }

    fun guardFront(taskId: Int, recents: Boolean): Boolean {
        val s = sessions.values.firstOrNull { taskId in it.taskIds } ?: return true
        if (authorized.get() === s || s.lease.state == DisplayLease.State.HUMAN) return true
        if (recents && s.lease.state == DisplayLease.State.RETAINED) {
            s.lease.takeover(); s.reason = "用户从最近任务接管"; return true
        }
        return false
    }

    fun fault(failure: Throwable) {
        healthy = false
        sessions.values.forEach { it.reason = "系统路由守卫失效：${failure.javaClass.simpleName}"; it.lease.lost() }
    }
}
