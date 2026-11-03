package io.github.mangi.eta.hook.system

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MibrainRecognitionHooksTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val mibrain = ComponentName(
        "com.xiaomi.mibrain.speech",
        "com.xiaomi.mibrain.speech.asr.AsrService",
    )

    @Test
    fun etaUidCanUseTheExactMibrainService() {
        install(ModuleConfig.ETA_PACKAGE, 10308)
        assertTrue(MibrainRecognitionHooks.isEtaMibrainBinding(context, mibrain, 10308))
    }

    @Test
    fun otherCallersAndOtherServicesKeepTheirOriginalBinding() {
        install(ModuleConfig.ETA_PACKAGE, 10308)
        install("com.example.other", 10309)
        assertFalse(MibrainRecognitionHooks.isEtaMibrainBinding(context, mibrain, 10309))
        assertFalse(MibrainRecognitionHooks.isEtaMibrainBinding(context, mibrain, 10310))
        assertFalse(MibrainRecognitionHooks.isEtaMibrainBinding(
            context, ComponentName(mibrain.packageName, "com.xiaomi.mibrain.speech.OtherService"), 10308,
        ))
        assertFalse(MibrainRecognitionHooks.isEtaMibrainBinding(
            context, ComponentName("com.example.other", mibrain.className), 10308,
        ))
    }

    private fun install(name: String, uid: Int) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.uid = uid
            }
        })
    }
}
