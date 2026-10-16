package io.github.mangi.eta.agent.mcp.server

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备端 MCP 服务的启停。服务运行在 App 进程内，运行期间持有前台执行租约以免进程被回收；
 * 用户在执行通知里点停止时连同服务一起停下，并把开关记为关闭。
 */
internal object EtaMcpServerController {
    sealed interface Status {
        data object Stopped : Status
        data class Running(
            val port: Int,
            val lanAccess: Boolean,
            /** 局域网模式下可供电脑端连接的 IPv4 地址；本机模式为空。 */
            val addresses: List<String>,
            /** 前台执行服务未能启动时为 false，App 退到后台后服务可能被系统停止。 */
            val keptAlive: Boolean,
        ) : Status
        data class Failed(val reason: String) : Status
    }

    private const val LEASE_ID = "mcp-server"
    private const val BIND_ATTEMPTS = 5
    private const val BIND_RETRY_DELAY_MS = 300L

    private val mutableStatus = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val mutableActivity = MutableStateFlow(EtaMcpActivity())
    /** 当前服务的电脑端连接情况；服务停止或重启后清空。 */
    val activity: StateFlow<EtaMcpActivity> = mutableActivity.asStateFlow()

    /** 启停都在单线程上串行执行，避免快速切换开关时两次绑定交错。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-mcp-control").apply { isDaemon = true }
    }
    private var running: RunningServer? = null

    private class RunningServer(
        val settings: EtaMcpServerSettings,
        val server: EtaMcpHttpServer,
        val protocol: EtaMcpProtocol,
    )

    /** 按当前保存的设置启动、重启或停止服务；设置未变时不重复绑定。 */
    fun applySettings(context: Context) {
        val appContext = context.applicationContext
        worker.execute { applyOnWorker(appContext, EtaMcpServerSettings.load()) }
    }

    private fun applyOnWorker(context: Context, settings: EtaMcpServerSettings) {
        val current = running
        if (settings.enabled && current?.settings == settings) return
        current?.let(::stopOnWorker)
        if (!settings.enabled || settings.token.isBlank()) {
            mutableStatus.value = Status.Stopped
            return
        }
        val protocol = EtaMcpProtocol(
            toolHost = EtaMcpDeviceToolHost(context),
            token = { settings.token },
            serverVersion = appVersion(context),
            onActivity = { mutableActivity.value = it },
        )
        val bindAddress = if (settings.lanAccess) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
        val server = EtaMcpHttpServer(
            bindAddress = bindAddress,
            port = settings.port,
            handler = protocol::handle,
            onHandlerFailure = { failure ->
                AndroidAgentLogger.warnThrottled("mcp_server_handler_failed") {
                    "MCP server request failed: type=${failure.safeLogType()}"
                }
            },
        )
        try {
            startWithRetry(server)
        } catch (failure: Exception) {
            protocol.close()
            AndroidAgentLogger.warn("MCP server start failed: type=${failure.safeLogType()}")
            mutableStatus.value = Status.Failed(failure.message ?: failure.javaClass.simpleName)
            return
        }
        running = RunningServer(settings, server, protocol)
        val keptAlive = AgentExecutionService.acquire(context, LEASE_ID) {
            // 用户从执行通知停止：关闭服务并记为关闭，避免下次打开 App 又自动启动。
            EtaMcpServerSettings.load().takeIf { it.enabled }?.let { EtaMcpServerSettings.save(it.copy(enabled = false)) }
            worker.execute { running?.let(::stopOnWorker); mutableStatus.value = Status.Stopped }
        }
        AndroidAgentLogger.info("MCP server started: lan=${settings.lanAccess}, port=${server.boundPort}")
        mutableStatus.value = Status.Running(
            port = server.boundPort,
            lanAccess = settings.lanAccess,
            addresses = if (settings.lanAccess) lanAddresses() else emptyList(),
            keptAlive = keptAlive,
        )
    }

    /** 刚关闭的监听可能仍短暂占用端口，绑定冲突时稍后重试；端口确被其他进程占用时最终仍报失败。 */
    private fun startWithRetry(server: EtaMcpHttpServer) {
        var attempt = 1
        while (true) {
            try {
                server.start()
                return
            } catch (failure: BindException) {
                if (attempt >= BIND_ATTEMPTS) throw failure
                attempt += 1
                Thread.sleep(BIND_RETRY_DELAY_MS)
            }
        }
    }

    private fun stopOnWorker(server: RunningServer) {
        server.server.close()
        server.protocol.close()
        mutableActivity.value = EtaMcpActivity()
        if (running === server) running = null
        AgentExecutionService.release(LEASE_ID)
        AndroidAgentLogger.info("MCP server stopped")
    }

    /** 刷新局域网地址（网络切换后调用）。 */
    fun refreshAddresses() {
        worker.execute {
            val current = mutableStatus.value as? Status.Running ?: return@execute
            if (current.lanAccess) mutableStatus.value = current.copy(addresses = lanAddresses())
        }
    }

    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }
}
