package io.github.mangi.eta.agent.display

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DisplaySessionStore {
    data class Snapshot(
        val session: String = "", val display: Int = -1, val user: Int = -1,
        val epoch: Long = 0, val state: String = "CLOSED", val reason: String = "尚未创建工作屏",
        val run: String = "",
    )
    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()
    private val anchor = Binder()
    private val nodeGate = DisplayActionGate()
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var broker: IBinder? = null
    @Volatile private var controller: AgentRunController? = null
    private const val RESOURCE = "work-display"
    fun enabled(context: Context): Boolean = context.getSharedPreferences("work_display", 0).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("work_display", 0).edit().putBoolean("enabled", enabled).apply()
    }

    @Synchronized fun create(context: Context): Snapshot {
        check(Looper.myLooper() != Looper.getMainLooper()) { "请在工作线程创建工作屏" }
        if (mutable.value.session.isNotEmpty() && broker != null) return refresh()
        if (broker == null) mutable.value = Snapshot()
        if (!AgentExecutionService.acquire(context, RESOURCE, retainedResource = true) { close() }) error("无法保持工作屏服务，请从 Eta 界面重试")
        try {
            val connection = connect(context)
            broker = connection
            val generation = connectionGeneration.incrementAndGet()
            connection.linkToDeath({
                if (broker === connection && generation == connectionGeneration.get()) {
                    broker = null
                    mutable.value = mutable.value.copy(state = "LOST", reason = "系统显示服务已断开；旧动作不会重放")
                    try { runCatching { controller?.pause() } }
                    finally { AgentExecutionService.release(RESOURCE) }
                }
            }, 0)
            return update(call("create", Bundle().apply { putBinder("client", anchor) }))
        } catch (failure: Exception) {
            broker = null
            AgentExecutionService.release(RESOURCE)
            throw failure
        }
    }

    fun acquire(context: Context, run: String, owner: AgentRunController): Snapshot {
        create(context)
        val snapshot = update(call("acquire", Bundle().apply { putString("run", run) }))
        controller = owner
        return snapshot
    }

    fun refresh(): Snapshot = update(call("status"))
    fun pause() {
        runCatching { controller?.pause() }
        if (broker != null && mutable.value.session.isNotEmpty()) {
            runCatching { nodeGate.commit { update(call("pause")) } }.onFailure {
                mutable.value = mutable.value.copy(state = "LOST", reason = it.message.orEmpty())
            }
        }
    }
    fun resume() {
        val snapshot = mutable.value
        check(snapshot.state == "PAUSED") { "只有暂停中的任务可以继续" }
        controller?.resume()
    }
    fun onRunPause(run: String, paused: Boolean) {
        if (mutable.value.run != run) return
        val result = runCatching { nodeGate.commit {
            update(call(if (paused) "pause" else "acquire", Bundle().apply { putString("run", run) }))
        } }
        if (!paused) result.getOrThrow()
        else result.onFailure { mutable.value = mutable.value.copy(state = "LOST", reason = it.message.orEmpty()) }
    }
    fun pauseRun(run: String, owner: AgentRunController, reason: String) {
        owner.pause()
        if (controller !== owner || mutable.value.run != run) return
        runCatching { nodeGate.commit { update(call("pause", Bundle().apply {
            putString("run", run); putString("reason", reason.take(500))
        })) } }
    }
    fun retain(run: String) {
        if (mutable.value.run != run) return
        runCatching { nodeGate.commit { update(call("retain", Bundle().apply { putString("run", run) })) } }
        if (mutable.value.run == run) controller = null
    }
    fun takeover() {
        pause()
        update(call("takeover"))
        controller?.cancel()
        controller = null
    }
    fun close() {
        try {
            pause()
            controller?.cancel()
            if (broker != null && mutable.value.session.isNotEmpty()) call("close")
        } finally {
            connectionGeneration.incrementAndGet()
            broker = null
            mutable.value = Snapshot()
            controller = null
            AgentExecutionService.release(RESOURCE)
        }
    }

    /** Use the observation's captured epoch. Never silently replace it with the current one. */
    fun action(op: String, run: String, epoch: Long, extras: Bundle = Bundle()): Snapshot {
        extras.putString("run", run)
        extras.putLong("epoch", epoch)
        extras.putString("operation_id", UUID.randomUUID().toString())
        return update(call(op, extras))
    }
    /** Pause acknowledgement and explicit task migration wait until node submission has returned.
     * Automatic launcher takeover is possible only after retain(), which uses this same gate.
     */
    fun <T> commitNode(run: String, epoch: Long, block: () -> T): T = nodeGate.commit {
        action("validate", run, epoch)
        block()
    }

    @Synchronized private fun update(result: Bundle): Snapshot {
        check(result.getBinder("_connection") === broker && broker != null) { "工作屏连接已更换" }
        check(result.getLong("_generation") == connectionGeneration.get()) { "工作屏连接代次已更换" }
        val snapshot = Snapshot(
            session = result.getString("session").orEmpty(), display = result.getInt("display", -1),
            user = result.getInt("user", -1), epoch = result.getLong("epoch"),
            state = result.getString("state").orEmpty(), reason = result.getString("reason").orEmpty(),
            run = result.getString("run").orEmpty(),
        )
        check(mutable.value.session.isEmpty() || snapshot.session == mutable.value.session) { "工作屏会话已更换" }
        if (snapshot.epoch >= mutable.value.epoch) mutable.value = snapshot
        AgentExecutionService.refresh()
        return mutable.value
    }

    private fun call(op: String, extras: Bundle = Bundle()): Bundle {
        val remote = broker ?: error("工作屏服务未连接；请检查 LSPosed 系统框架作用域并重启手机")
        val generation = connectionGeneration.get()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            extras.putInt("version", DisplayProtocol.VERSION)
            extras.putString("op", op)
            extras.putString("session", mutable.value.session)
            data.writeInterfaceToken(DisplayProtocol.DESCRIPTOR)
            data.writeBundle(extras)
            check(remote.transact(DisplayProtocol.TRANSACT, data, reply, 0)) { "工作屏协议不受支持" }
            reply.readException()
            val result = reply.readBundle(DisplaySessionStore::class.java.classLoader) ?: error("工作屏返回空结果")
            check(result.getBoolean("ok")) { result.getString("message") ?: "工作屏操作失败；请重新观察" }
            result.putBinder("_connection", remote)
            result.putLong("_generation", generation)
            return result
        } finally { data.recycle(); reply.recycle() }
    }

    private fun connect(context: Context): IBinder {
        val latch = CountDownLatch(1)
        var result: IBinder? = null
        context.sendOrderedBroadcast(
            Intent(DisplayProtocol.ACTION).setPackage("android").putExtra("version", DisplayProtocol.VERSION),
            null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    if (resultCode == 1) result = getResultExtras(false)?.getBinder("broker")
                    latch.countDown()
                }
            }, Handler(Looper.getMainLooper()), 0, null, null,
        )
        check(latch.await(4, TimeUnit.SECONDS)) { "工作屏服务连接超时" }
        return result ?: error("工作屏服务不可用，请在 LSPosed 启用 Eta 的系统框架作用域并重启")
    }
}
