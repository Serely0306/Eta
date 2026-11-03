package io.github.mangi.eta.hook.system

import android.content.ComponentName
import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

internal object MibrainRecognitionHooks {
    private const val REMOTE_SERVICE_CLASS =
        "com.android.server.speech.RemoteSpeechRecognitionService"
    private const val MIBRAIN_PACKAGE = "com.xiaomi.mibrain.speech"
    private const val MIBRAIN_SERVICE = "com.xiaomi.mibrain.speech.asr.AsrService"

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation = HookRegistrar(module, logger, "MibrainRecognition").install {
        val serviceClass = HookSupport.findClassOrNull(classLoader, REMOTE_SERVICE_CLASS)
        if (serviceClass == null) {
            missing(
                id = "system.mibrain-recognition",
                description = "Eta mibrain recognition binding",
                detail = "未找到 $REMOTE_SERVICE_CLASS，mibrain 识别绑定适配未安装",
            )
            return@install
        }
        val constructor = serviceClass.getDeclaredConstructor(
            Context::class.java,
            ComponentName::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        ).apply { isAccessible = true }
        intercept(
            id = "system.mibrain-recognition",
            executable = constructor,
            description = "Eta mibrain recognition binding",
        ) { chain ->
            if (chain.args[4] == true || !isEtaMibrainBinding(
                    context = chain.args[0] as Context,
                    service = chain.args[1] as ComponentName,
                    callingUid = chain.args[3] as Int,
                )
            ) {
                return@intercept chain.proceed()
            }
            val args = chain.args.toMutableList()
            args[4] = true
            val result = chain.proceed(args.toTypedArray())
            this.logger.info("已为 Eta 调用的 mibrain 识别服务启用特权绑定")
            result
        }
    }

    internal fun isEtaMibrainBinding(context: Context, service: ComponentName, callingUid: Int): Boolean =
        service.packageName == MIBRAIN_PACKAGE && service.className == MIBRAIN_SERVICE &&
            context.packageManager.getPackagesForUid(callingUid)?.contains(ModuleConfig.ETA_PACKAGE) == true
}
