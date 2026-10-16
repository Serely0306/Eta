package io.github.mangi.eta.hook.xiaoai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal object XiaoAiHooks {
    private const val OPERATION_MANAGER_CLASS =
        "com.xiaomi.voiceassistant.instruction.base.OperationManager"
    private const val APPLICATION_CLASS = "com.xiaomi.voiceassistant.VAApplication"
    private const val EVENT_CLASS = "com.xiaomi.ai.api.common.Event"
    private const val INSTRUCTION_CLASS = "com.xiaomi.ai.api.common.Instruction"
    private const val ASR_RESULT_FULL_NAME = "SpeechRecognizer.RecognizeResult"
    private const val CARD_BASE_CLASS = "com.xiaomi.voiceassistant.card.a"
    private const val UI_MANAGER_CLASS = "com.xiaomi.voiceassistant.UiManager"
    private const val EXTRA_IMAGE_FILE_ID = "extra_image_file_id"
    private val DOCUMENT_EXTRA_KEYS = setOf("file_path", "file_name", "file_desc", "query_type")
    private const val BRIDGE_QUEUE_CAPACITY = 4
    private const val ACTIVATION_TIMEOUT_SECONDS = 3L
    private const val PENDING_DRAIN_LIMIT = 8

    private val queryCache = XiaoAiQueryCache()
    private val turnTracker = XiaoAiTurnTracker()
    private val claimedDialogIds = XiaoAiRecentIds()
    private val voiceTakeoverState = XiaoAiVoiceTakeoverState()
    private val requestClaimLock = Any()
    private val activeRun = XiaoAiRunSlot<ActiveRun>()
    private val latestFloatManager = AtomicReference<Any?>()
    private val rendererSlot = XiaoAiRendererSlot<XiaoAiStreamRenderer>()
    private val bridgeThreadId = AtomicInteger()
    private val businessHooksInstalled = AtomicBoolean(false)
    private val deferredHookHandles = CopyOnWriteArrayList<HookHandle>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(BRIDGE_QUEUE_CAPACITY),
        { runnable ->
            Thread(
                runnable,
                "Eta-XiaoAiBridge-${bridgeThreadId.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }
    /** 旧任务的取消请求单独排队：桥接执行器的唯一线程正被旧任务占用。 */
    private val cancelExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(BRIDGE_QUEUE_CAPACITY),
        { runnable ->
            Thread(
                runnable,
                "Eta-XiaoAiCancel-${bridgeThreadId.incrementAndGet()}",
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "XiaoAi")
        return hooks.install {
            hookApplicationBootstrap(
                hooks = this,
                module = module,
                rootLogger = rootLogger,
                classLoader = classLoader,
            )
        }
    }

    private fun hookApplicationBootstrap(
        hooks: HookRegistrar,
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ) {
        val applicationClass = HookSupport.findClassOrNull(classLoader, APPLICATION_CLASS)
        val onCreate = applicationClass?.let { HookSupport.findMethod(it, "onCreate") }
        if (onCreate == null || onCreate.parameterCount != 0) {
            hooks.missing(
                id = "xiaoai.bootstrap",
                description = "VAApplication.onCreate",
                detail = "未找到超级小爱 Application 版本门禁入口",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.bootstrap",
            executable = onCreate,
            description = "XiaoAi VAApplication.onCreate",
        ) { chain ->
            val context = chain.thisObject as? Context
            val versionCode = context?.let(::packageVersionCode) ?: -1L
            val targets = XiaoAiTargets.forVersion(versionCode)
            if (targets != null) {
                installBusinessHooksOnce(
                    module = module,
                    rootLogger = rootLogger,
                    classLoader = classLoader,
                    targets = targets,
                )
            } else {
                hooks.logger.warnThrottled("xiaoai_unsupported_version") {
                    "超级小爱版本不在静态适配范围，保持原生行为: versionCode=$versionCode"
                }
            }
            chain.proceed()
        }
    }

    private fun installBusinessHooksOnce(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        if (!businessHooksInstalled.compareAndSet(false, true)) return
        val hooks = HookRegistrar(module, rootLogger, "XiaoAi")
        val installation = hooks.install {
            hookQueryCapture(this, classLoader)
            hookAsrFinalResult(this, classLoader, targets)
            if (hookVoiceInstructionFilter(this, classLoader, targets)) {
                hookVoiceAsrDispatch(this, classLoader, targets)
            }
            hookFloatManagerCapture(this, classLoader, targets)
            hookAgentActions(this, classLoader, targets)
            hookOutboundRequest(this, classLoader, targets)
            hookSessionClear(this, classLoader, targets)
            schedulePendingResultDrains(logger, classLoader, targets)
        }
        deferredHookHandles += installation.handles
        rootLogger.scoped("XiaoAi").info(installation.report.summary())
    }

    private fun packageVersionCode(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            ).longVersionCode
        }.getOrDefault(-1L)

    private fun hookQueryCapture(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
    ) {
        val managerClass = HookSupport.findClassOrNull(classLoader, OPERATION_MANAGER_CLASS)
        if (managerClass == null) {
            hooks.missing(
                id = "xiaoai.query-info",
                description = "OperationManager.setQueryInfo",
                detail = "未找到小爱查询信息入口",
            )
            return
        }
        val method = HookSupport.findMethod(
            managerClass,
            "setQueryInfo",
            String::class.java,
            String::class.java,
            JSONObject::class.java,
        )
        if (method == null) {
            hooks.missing(
                id = "xiaoai.query-info",
                description = "OperationManager.setQueryInfo",
                detail = "未找到 setQueryInfo(String,String,JSONObject)",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.query-info",
            executable = method,
            description = "XiaoAi OperationManager.setQueryInfo",
        ) { chain ->
            val dialogId = chain.args.getOrNull(0) as? String
            val query = chain.args.getOrNull(1) as? String
            val extra = chain.args.getOrNull(2) as? JSONObject
            if (!dialogId.isNullOrBlank() && !query.isNullOrBlank()) {
                val imageFileId = extra?.optString(EXTRA_IMAGE_FILE_ID)
                val documentInput = extra?.let { json ->
                    DOCUMENT_EXTRA_KEYS.any(json::has)
                } == true
                queryCache.put(
                    dialogId = dialogId,
                    query = query,
                    imageFileId = imageFileId,
                    documentInput = documentInput,
                )
                turnTracker.capture(
                    dialogId = dialogId,
                    query = query,
                    hasImage = !imageFileId.isNullOrBlank(),
                    documentInput = documentInput,
                )
                hooks.logger.debug {
                    "捕获超级小爱查询: dialog=${shortId(dialogId)}, queryChars=${query.length}"
                }
            }
            chain.proceed()
        }
    }

    private fun hookAsrFinalResult(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val processorClass = HookSupport.findClassOrNull(classLoader, targets.asrProcessorClass)
        val instructionClass = HookSupport.findClassOrNull(classLoader, INSTRUCTION_CLASS)
        if (processorClass == null || instructionClass == null) {
            hooks.missing(
                id = "xiaoai.asr-final",
                description = "ASR final result",
                detail = "未找到超级小爱终态 ASR 处理器",
            )
            return
        }
        val method = HookSupport.findMethod(processorClass, "processed", instructionClass)
        if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.missing(
                id = "xiaoai.asr-final",
                description = "ASR final result",
                detail = "未找到 ${targets.asrProcessorClass}.processed(Instruction): boolean",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.asr-final",
            executable = method,
            description = "XiaoAi final ASR result",
        ) { chain ->
            val instruction = chain.args.firstOrNull()
            if (instruction != null) {
                captureFinalAsr(hooks.logger, instruction)
            }
            chain.proceed()
        }
    }

    private fun captureFinalAsr(
        logger: ModuleLogger,
        instruction: Any,
    ) {
        if (invokeString(instruction, "getFullName") != ASR_RESULT_FULL_NAME) return
        val payload = HookSupport.invokeNoArgs(instruction, "getPayload") ?: return
        val results = HookSupport.invokeNoArgs(payload, "getResults") as? Iterable<*> ?: return
        val texts = results.map { item ->
            item?.let { invokeString(it, "getText") }.orEmpty()
        }
        val finalAsr = XiaoAiFinalAsr.from(
            dialogId = optionalString(HookSupport.invokeNoArgs(instruction, "getDialogId")),
            isFinal = HookSupport.invokeNoArgs(payload, "isFinal") == true,
            results = texts,
        ) ?: return
        val previousTurn = turnTracker.latest()?.takeIf { it.dialogId == finalAsr.dialogId }
        val queryInfo = queryCache.peek(finalAsr.dialogId)
        turnTracker.capture(
            dialogId = finalAsr.dialogId,
            query = finalAsr.query,
            hasImage = previousTurn?.hasImage == true || !queryInfo?.imageFileId.isNullOrBlank(),
            documentInput = previousTurn?.documentInput == true || queryInfo?.documentInput == true,
        )
        logger.debug {
            "捕获超级小爱终态 ASR: dialog=${shortId(finalAsr.dialogId)}, " +
                "queryChars=${finalAsr.query.length}"
        }
    }

    private fun hookVoiceInstructionFilter(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ): Boolean {
        val filterClassName = targets.instructionFilterClass ?: return false
        val filterClass = HookSupport.findClassOrNull(classLoader, filterClassName)
        val instructionClass = HookSupport.findClassOrNull(classLoader, INSTRUCTION_CLASS)
        val method = if (filterClass != null && instructionClass != null) {
            HookSupport.findMethod(
                filterClass,
                "filterInstruction",
                instructionClass,
                String::class.java,
            )
        } else {
            null
        }
        if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.missing(
                id = "xiaoai.voice-filter",
                description = "XiaoAi voice instruction filter",
                detail = "未找到 $filterClassName.filterInstruction(Instruction,String): boolean",
            )
            return false
        }
        return hooks.intercept(
            id = "xiaoai.voice-filter",
            executable = method,
            description = "XiaoAi claimed voice dialog filter",
        ) { chain ->
            val instruction = chain.args.firstOrNull()
            val dialogId = instruction?.let {
                optionalString(HookSupport.invokeNoArgs(it, "getDialogId"))
            }.orEmpty()
            val fullName = instruction?.let { invokeString(it, "getFullName") }.orEmpty()
            if (voiceTakeoverState.shouldSuppress(dialogId, fullName)) {
                hooks.logger.debug {
                    "已过滤超级小爱原生语音指令: dialog=${shortId(dialogId)}, name=$fullName"
                }
                true
            } else {
                chain.proceed()
            }
        } != null
    }

    private fun hookVoiceAsrDispatch(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val methodName = targets.asrDispatchMethod ?: return
        val engineFieldName = targets.asrEngineField ?: return
        val requestModeFieldName = targets.engineRequestModeField ?: return
        val processorClass = HookSupport.findClassOrNull(classLoader, targets.asrProcessorClass)
        val engineClass = HookSupport.findClassOrNull(classLoader, targets.engineClass)
        val instructionClass = HookSupport.findClassOrNull(classLoader, INSTRUCTION_CLASS)
        val recognizeResultClass = HookSupport.findClassOrNull(
            classLoader,
            "com.xiaomi.ai.api.SpeechRecognizer\$RecognizeResult",
        )
        val speechResultClass = targets.asrSpeechResultClass?.let {
            HookSupport.findClassOrNull(classLoader, it)
        }
        val method = if (
            processorClass != null && instructionClass != null &&
            recognizeResultClass != null && speechResultClass != null
        ) {
            HookSupport.findMethod(
                processorClass,
                methodName,
                instructionClass,
                recognizeResultClass,
                List::class.java,
                speechResultClass,
                String::class.java,
            )
        } else {
            null
        }
        val engineField = processorClass?.let { HookSupport.findField(it, engineFieldName) }
        val requestModeField = engineClass?.let { HookSupport.findField(it, requestModeFieldName) }
        if (
            method == null || method.returnType != Void.TYPE || engineClass == null ||
            engineField == null || requestModeField == null || engineField.type != engineClass ||
            requestModeField.type != Int::class.javaPrimitiveType
        ) {
            hooks.missing(
                id = "xiaoai.voice-asr",
                description = "XiaoAi dispatched voice ASR",
                detail = "未找到 ${targets.asrProcessorClass}.$methodName 的终态 ASR 分发签名",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.voice-asr",
            executable = method,
            description = "XiaoAi dispatched final voice ASR",
        ) { chain ->
            try {
                val payload = chain.args.getOrNull(1)
                val result = chain.args.getOrNull(3)
                val finalAsr = XiaoAiFinalAsr.from(
                    dialogId = chain.args.getOrNull(4) as? String ?: "",
                    isFinal = payload?.let { HookSupport.invokeNoArgs(it, "isFinal") } == true,
                    results = listOf(result?.let { invokeString(it, "getQuery") }.orEmpty()),
                )
                if (finalAsr != null) {
                    val engine = chain.thisObject?.let { engineField.get(it) }
                    val enginePrefix = engine?.let { invokeString(it, "getEnginePrefix") }.orEmpty()
                    val requestMode = engine?.let { requestModeField.get(it) as? Int }
                    maybeClaimVoice(
                        hooks.logger, classLoader, targets, finalAsr, enginePrefix, requestMode,
                    )
                }
            } catch (exception: Exception) {
                hooks.logger.warnThrottled("xiaoai_voice_claim_failed") {
                    "小爱语音认领失败，保持原生行为: type=${exception.safeLogType()}"
                }
            }
            chain.proceed()
        }
    }

    private fun maybeClaimVoice(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
        finalAsr: XiaoAiFinalAsr,
        enginePrefix: String,
        requestMode: Int?,
    ): Boolean = synchronized(requestClaimLock) {
        val queryInfo = queryCache.peek(finalAsr.dialogId)
        val turn = turnTracker.latest()?.takeIf { it.dialogId == finalAsr.dialogId }
        if (!XiaoAiVoiceInputPolicy.canClaim(
                enginePrefix = enginePrefix,
                requestMode = requestMode,
                hasImage = turn?.hasImage == true || !queryInfo?.imageFileId.isNullOrBlank(),
                documentInput = turn?.documentInput == true || queryInfo?.documentInput == true,
            )
        ) return@synchronized false
        voiceTakeoverState.tryClaim(finalAsr.dialogId) {
            startVoiceRun(logger, classLoader, targets, finalAsr)
        }
    }

    private fun startVoiceRun(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
        finalAsr: XiaoAiFinalAsr,
    ): Boolean {
        if (claimedDialogIds.contains(finalAsr.dialogId)) return true
        val decision = XiaoAiTakeoverPolicy.decide(
            query = finalAsr.query,
            hasImage = false,
            customModelEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
            requirePrefix = Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX),
        ) ?: return false
        val started = startRun(
            logger = logger,
            classLoader = classLoader,
            targets = targets,
            dialogId = finalAsr.dialogId,
            prompt = decision.prompt,
            image = null,
        )
        if (started) {
            logger.info(
                "已接管超级小爱语音: dialog=${shortId(finalAsr.dialogId)}, " +
                    "queryChars=${finalAsr.query.length}",
            )
        }
        return started
    }

    private fun hookFloatManagerCapture(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val floatManagerClass = HookSupport.findClassOrNull(classLoader, targets.floatManagerClass)
        val cardClass = HookSupport.findClassOrNull(classLoader, CARD_BASE_CLASS)
        if (floatManagerClass == null || cardClass == null) {
            hooks.missing(
                id = "xiaoai.card-sink",
                description = "FloatManager.addCard",
                detail = "未找到小爱卡片管理器",
            )
            return
        }
        val method = HookSupport.findMethod(floatManagerClass, "addCard", cardClass)
        if (method == null) {
            hooks.missing(
                id = "xiaoai.card-sink",
                description = "FloatManager.addCard",
                detail = "未找到 FloatManager.addCard(Card)",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.card-sink",
            executable = method,
            description = "XiaoAi FloatManager.addCard",
        ) { chain ->
            chain.thisObject?.let(latestFloatManager::set)
            chain.proceed()
        }
    }

    private fun hookOutboundRequest(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val sendMethodName = targets.engineSendMethod
        val engineClass = HookSupport.findClassOrNull(classLoader, targets.engineClass)
        val eventClass = HookSupport.findClassOrNull(classLoader, EVENT_CLASS)
        if (engineClass == null || eventClass == null) {
            hooks.missing(
                id = "xiaoai.outbound",
                description = "MiSpeechEngine.$sendMethodName",
                detail = "未找到小爱出站事件边界",
            )
            return
        }
        val method = HookSupport.findMethod(engineClass, sendMethodName, eventClass)
        if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
            hooks.missing(
                id = "xiaoai.outbound",
                description = "MiSpeechEngine.$sendMethodName",
                detail = "未找到 $sendMethodName(Event): boolean",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.outbound",
            executable = method,
            description = "XiaoAi MiSpeechEngine.$sendMethodName",
        ) { chain ->
            val event = chain.args.getOrNull(0)
            val claimed = try {
                event != null && maybeClaimRequest(hooks.logger, classLoader, targets, event)
            } catch (exception: Exception) {
                hooks.logger.warnThrottled("xiaoai_claim_failed") {
                    "小爱请求认领失败，保持原生行为: type=${exception.safeLogType()}"
                }
                false
            }
            if (claimed) true else chain.proceed()
        }
    }

    private fun hookAgentActions(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val managerClass = HookSupport.findClassOrNull(classLoader, targets.agentActionManagerClass)
        if (managerClass == null) {
            hooks.missing(
                id = "xiaoai.agent-action",
                description = "AgentActionManager",
                detail = "未找到超级小爱 Agent Action 执行器",
            )
            return
        }
        val methods = HookSupport.findDeclaredMethods(managerClass, makeAccessible = true) { method ->
            (method.name == "execute" || method.name == "executeActionsAsync") &&
                method.returnType == Boolean::class.javaPrimitiveType &&
                method.parameterTypes.any { it.name.endsWith("Agent\$Action") } &&
                (targets.instructionFilterClass == null ||
                    (method.parameterTypes.firstOrNull()?.name?.endsWith("Agent\$Action") == true &&
                        method.parameterTypes.getOrNull(1) == String::class.java))
        }
        if (methods.isEmpty()) {
            hooks.missing(
                id = "xiaoai.agent-action",
                description = "AgentActionManager",
                detail = "未找到 Agent Action boolean 执行入口",
            )
            return
        }
        methods.forEachIndexed { index, method ->
            hooks.intercept(
                id = "xiaoai.agent-action.$index",
                executable = method,
                description = "XiaoAi ${method.name}(Agent.Action)",
            ) { chain ->
                val ownsAction = if (targets.instructionFilterClass != null) {
                    val dialogId = chain.args.getOrNull(1) as? String ?: ""
                    !XiaoAiEtaOutput.isActive() &&
                        (claimedDialogIds.contains(dialogId) || voiceTakeoverState.isClaimed(dialogId))
                } else {
                    shouldOwnLatestTurn()
                }
                if (ownsAction) {
                    hooks.logger.info("已拦截超级小爱原生 Agent Action")
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun hookSessionClear(
        hooks: HookRegistrar,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val engineClass = HookSupport.findClassOrNull(classLoader, targets.engineClass)
        val method = engineClass?.let { HookSupport.findMethod(it, targets.engineClearMethod) }
        if (method == null || method.parameterCount != 0) {
            hooks.missing(
                id = "xiaoai.session-clear",
                description = "MiSpeechEngine session clear",
                detail = "未找到小爱会话清理入口",
            )
            return
        }
        hooks.intercept(
            id = "xiaoai.session-clear",
            executable = method,
            description = "XiaoAi MiSpeechEngine session clear",
        ) { chain ->
            // BACK 关闭小爱浮层也会清理 session；这里只分离入口 UI，不能取消仍在执行的 Runtime。
            rendererSlot.detach()?.cancel(classLoader)
            queryCache.clear()
            turnTracker.clear()
            claimedDialogIds.clear()
            chain.proceed()
        }
    }

    private fun maybeClaimRequest(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
        event: Any,
    ): Boolean = synchronized(requestClaimLock) {
        maybeClaimRequestLocked(logger, classLoader, targets, event)
    }

    private fun maybeClaimRequestLocked(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
        event: Any,
    ): Boolean {
        val fullName = invokeString(event, "getFullName")
        if (!XiaoAiTakeoverPolicy.matchesOutboundEvent(fullName)) return false

        val dialogId = invokeString(event, "getId").trim()
        if (dialogId.isBlank()) return false
        if (claimedDialogIds.contains(dialogId) || voiceTakeoverState.isClaimed(dialogId)) return true

        val payload = HookSupport.invokeNoArgs(event, "getPayload")
        val eventQuery = payload?.let { invokeString(it, "getQuery") }.orEmpty()
        val queryInfo = queryCache.takeMatching(dialogId, eventQuery)
        val latestTurn = turnTracker.latest()
        val query = eventQuery.ifBlank { queryInfo?.query ?: latestTurn?.query.orEmpty() }
        if (query.isBlank()) {
            logger.warnThrottled("xiaoai_nlp_query_missing") {
                "超级小爱 Nlp.Request 缺少可接管的查询文本"
            }
            return false
        }
        if (queryInfo == null && query.equals("blank", ignoreCase = true)) {
            logger.warnThrottled("xiaoai_blank_query_uncorrelated") {
                "超级小爱 blank 请求缺少图片上下文，保持原生行为"
            }
            return false
        }
        val sameTrackedQuery = latestTurn?.query?.trim() == query.trim()
        if (queryInfo?.documentInput == true ||
            (queryInfo == null && sameTrackedQuery && latestTurn.documentInput)
        ) {
            return false
        }
        if (queryInfo == null && sameTrackedQuery && latestTurn.hasImage) {
            logger.warnThrottled("xiaoai_image_query_uncorrelated") {
                "超级小爱图片请求无法关联 setQueryInfo，保持原生行为"
            }
            return false
        }
        if (queryInfo != null && queryInfo.dialogId != dialogId) {
            logger.debug {
                "关联超级小爱新 Event ID: queryDialog=${shortId(queryInfo.dialogId)}, " +
                    "event=${shortId(dialogId)}"
            }
        } else if (queryInfo == null) {
            logger.debug {
                "直接从 Nlp.Request 捕获超级小爱查询: event=${shortId(dialogId)}, " +
                    "queryChars=${query.length}"
            }
        }

        val imageResolution = resolveImage(
            logger = logger,
            classLoader = classLoader,
            imageIdStoreClass = targets.imageIdStoreClass,
            imageFileId = queryInfo?.imageFileId,
        )
        if (imageResolution is XiaoAiImages.Resolution.Failure) {
            logger.warnThrottled("xiaoai_image_${imageResolution.code.name.lowercase()}") {
                "小爱图片认领前校验失败，保持原生行为: code=${imageResolution.code.name}"
            }
            return false
        }
        val image = (imageResolution as? XiaoAiImages.Resolution.Success)?.image
        val decision = XiaoAiTakeoverPolicy.decide(
            query = query,
            hasImage = image != null,
            customModelEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
            requirePrefix = Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX),
        ) ?: return false
        turnTracker.capture(
            dialogId = dialogId,
            query = query,
            hasImage = image != null,
        )
        return startRun(logger, classLoader, targets, dialogId, decision.prompt, image)
    }

    private fun startRun(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
        dialogId: String,
        prompt: String,
        image: AgentModelClient.ModelImage?,
    ): Boolean {
        val context = AgentAppContext.resolve() ?: return false
        val renderer = XiaoAiStreamRenderer(
            context = context,
            logger = logger,
            classLoader = classLoader,
            targets = targets,
            dialogId = dialogId,
            floatManagerProvider = {
                latestFloatManager.get() ?: resolveFloatManager(classLoader, context)
            },
        )
        val run = ActiveRun(
            runId = UUID.randomUUID().toString(),
            dialogId = dialogId,
            prompt = prompt,
            image = image,
            renderer = renderer,
        )
        val future = try {
            executor.submit {
                executeRun(logger, context, run)
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        run.future.set(future)
        rendererSlot.attach(renderer)?.cancel()
        val previous = activeRun.replace(run)
        // 先登记并激活新任务，再收尾旧任务：旧任务的取消失败不能让新任务悬空。
        claimedDialogIds.add(dialogId)
        run.activate()
        previous?.let { old ->
            runCatching { old.cancel(logger, classLoader) }.onFailure { exception ->
                logger.warnThrottled("xiaoai_previous_cancel_failed") {
                    "超级小爱上一轮取消失败: type=${exception.safeLogType()}"
                }
            }
        }
        logger.info("已接管超级小爱请求: images=${if (image == null) 0 else 1}")
        return true
    }

    private fun cancelInBackground(logger: ModuleLogger, block: () -> Unit) {
        try {
            cancelExecutor.execute { runCatching(block) }
        } catch (_: RejectedExecutionException) {
            // 被打断的旧任务线程在 AgentRuntimeClient.run 中仍会自行发送取消。
            logger.warnThrottled("xiaoai_cancel_rejected") {
                "超级小爱取消队列繁忙，依赖旧任务线程中断后的取消"
            }
        }
    }

    private fun shouldOwnLatestTurn(): Boolean {
        val turn = turnTracker.latest() ?: return activeRun.get() != null
        if (turn.documentInput) return false
        return XiaoAiTakeoverPolicy.decide(
            query = turn.query,
            hasImage = turn.hasImage,
            customModelEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_CUSTOM_MODEL),
            requirePrefix = Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX),
        ) != null
    }

    private fun executeRun(
        logger: ModuleLogger,
        context: Context,
        run: ActiveRun,
    ) {
        if (!run.awaitActivation() || activeRun.get() !== run) {
            // 未激活就退出时同样释放占位，避免后续判断一直看到一个永远不会运行的任务。
            activeRun.clear(run)
            return
        }
        val client = AgentRuntimeClient(context, logger)
        run.client.set(client)
        var resultRunId: String? = null
        try {
            val result = client.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = run.runId,
                    prompt = run.prompt,
                    config = AgentModelClient.loadConfig(),
                    images = listOfNotNull(run.image),
                    handoff = run.toHandoff(),
                ),
                onEvent = { event ->
                    if (activeRun.get() === run && !run.cancelled.get()) {
                        run.renderer.onEvent(event)
                    }
                },
            )
            resultRunId = result.runId.ifBlank { run.runId }
            if (activeRun.get() !== run || run.cancelled.get()) return
            if (result.ok) {
                run.renderer.complete(result.content)
            } else {
                logger.warnThrottled("xiaoai_runtime_failed") {
                    "超级小爱 Agent Runtime 执行失败"
                }
                run.renderer.fail()
            }
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_bridge_failed") {
                "超级小爱 Agent 执行异常: type=${exception.safeLogType()}"
            }
            if (activeRun.get() === run && !run.cancelled.get()) {
                run.renderer.fail()
            }
        } finally {
            resultRunId?.let { client.ackResult(it) }
            activeRun.clear(run)
        }
    }

    private fun resolveImage(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        imageIdStoreClass: String,
        imageFileId: String?,
    ): XiaoAiImages.Resolution {
        val normalizedId = imageFileId?.trim().orEmpty()
        if (normalizedId.isBlank()) return XiaoAiImages.Resolution.NoImage
        val storeClass = HookSupport.findClassOrNull(classLoader, imageIdStoreClass)
            ?: return XiaoAiImages.missingPath()
        val getString = HookSupport.findMethod(
            storeClass,
            "getString",
            String::class.java,
            String::class.java,
        ) ?: return XiaoAiImages.missingPath()
        val path = try {
            getString.invoke(null, normalizedId, "") as? String
        } catch (exception: Exception) {
            logger.warnThrottled("xiaoai_image_lookup_failed") {
                "小爱图片映射读取失败: type=${exception.safeLogType()}"
            }
            null
        }
        if (path.isNullOrBlank()) return XiaoAiImages.missingPath()
        return XiaoAiImages.validatePath(path)
    }

    private fun schedulePendingResultDrains(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
    ) {
        val handler = Handler(Looper.getMainLooper())
        longArrayOf(1_000L, 5_000L, 15_000L).forEach { delay ->
            handler.postDelayed({
                val context = AgentAppContext.resolve() ?: return@postDelayed
                try {
                    executor.execute {
                        drainPendingResults(logger, classLoader, targets, context)
                    }
                } catch (_: RejectedExecutionException) {
                    logger.warnThrottled("xiaoai_drain_rejected") {
                        "超级小爱结果恢复队列繁忙，跳过本次恢复"
                    }
                }
            }, delay)
        }
    }

    private fun drainPendingResults(
        logger: ModuleLogger,
        classLoader: ClassLoader,
        targets: XiaoAiTargets,
        context: Context,
    ) {
        val client = AgentRuntimeClient(context, logger)
        client.drainCompletedRuns()
            .asSequence()
            .filter { it.handoff.source == XiaoAiHandoff.SOURCE }
            .take(PENDING_DRAIN_LIMIT)
            .forEach { completed ->
                val dialogId = dialogIdFromHandoff(completed.handoff)
                if (
                    completed.result.ok &&
                    dialogId.isNotBlank() &&
                    dialogId == currentDialogId(classLoader)
                ) {
                    val renderer = XiaoAiStreamRenderer(
                        context = context,
                        logger = logger,
                        classLoader = classLoader,
                        targets = targets,
                        dialogId = dialogId,
                        floatManagerProvider = {
                            latestFloatManager.get() ?: resolveFloatManager(classLoader, context)
                        },
                    )
                    rendererSlot.attach(renderer)?.cancel()
                    renderer.complete(completed.result.content)
                }
                client.ackResult(completed.result.runId.ifBlank { completed.handoff.id })
            }
    }

    private fun currentDialogId(classLoader: ClassLoader): String {
        val managerClass = HookSupport.findClassOrNull(classLoader, OPERATION_MANAGER_CLASS)
            ?: return ""
        val instance = runCatching {
            HookSupport.findMethod(managerClass, "getInstance")?.invoke(null)
        }.getOrNull() ?: return ""
        val queryInfo = HookSupport.invokeNoArgs(instance, "getQueryInfo") ?: return ""
        return invokeString(queryInfo, "getDialogId")
    }

    private fun dialogIdFromHandoff(handoff: AgentRuntimeWire.EntryHandoff): String {
        return XiaoAiHandoff.dialogIdFrom(handoff)
    }

    private fun resolveFloatManager(
        classLoader: ClassLoader,
        context: Context,
    ): Any? = runCatching {
        val uiManagerClass = Class.forName(UI_MANAGER_CLASS, false, classLoader)
        val getInstance = uiManagerClass.getMethod("getInstance", Context::class.java)
        val uiManager = getInstance.invoke(null, context) ?: return@runCatching null
        uiManager.javaClass.getMethod("getFloatManager").invoke(uiManager)
    }.getOrNull()?.also(latestFloatManager::set)

    private fun invokeString(target: Any, methodName: String): String =
        (HookSupport.invokeNoArgs(target, methodName) as? String).orEmpty()

    private fun optionalString(optional: Any?): String {
        if (optional == null || HookSupport.invokeNoArgs(optional, "isPresent") != true) return ""
        return HookSupport.invokeNoArgs(optional, "get")?.toString().orEmpty()
    }

    private fun shortId(value: String): String =
        value.trim().let { id ->
            when {
                id.isBlank() -> "-"
                id.length <= 8 -> id
                else -> "${id.take(4)}…${id.takeLast(4)}"
            }
        }

    private data class ActiveRun(
        val runId: String,
        val dialogId: String,
        val prompt: String,
        val image: AgentModelClient.ModelImage?,
        val renderer: XiaoAiStreamRenderer,
        val activation: CountDownLatch = CountDownLatch(1),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val client: AtomicReference<AgentRuntimeClient?> = AtomicReference(),
        val future: AtomicReference<Future<*>?> = AtomicReference(),
    ) {
        fun activate() {
            activation.countDown()
        }

        fun awaitActivation(): Boolean =
            activation.await(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        /**
         * 只做非阻塞收尾：调用方可能持有认领锁或处在小爱指令线程上，Runtime 取消请求需要
         * 绑定服务（主线程上直接拒绝），放到独立线程发出。
         */
        fun cancel(logger: ModuleLogger, classLoader: ClassLoader) {
            if (!cancelled.compareAndSet(false, true)) return
            activation.countDown()
            client.get()?.let { runtime -> cancelInBackground(logger) { runtime.cancelRun(runId) } }
            future.get()?.cancel(true)
            renderer.cancel(classLoader)
            logger.debug { "超级小爱请求已取消" }
        }

        fun toHandoff(): AgentRuntimeWire.EntryHandoff =
            XiaoAiHandoff.create(
                runId = runId,
                dialogId = dialogId,
                prompt = prompt,
            )
    }
}
