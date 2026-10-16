package io.github.mangi.eta.hook.xiaoai

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoAiVoiceTakeoverStateTest {
    @Test
    fun voiceInputPolicyAcceptsOnlyMainEngineConversationModes() {
        listOf(3, 7, 19, 23).forEach { mode ->
            assertTrue(XiaoAiVoiceInputPolicy.canClaim("main", mode, false, false))
        }
        listOf(null, 0, 1, 2, 4, 6, 8, 16, 99).forEach { mode ->
            assertFalse(XiaoAiVoiceInputPolicy.canClaim("main", mode, false, false))
        }
        assertFalse(XiaoAiVoiceInputPolicy.canClaim("", 23, false, false))
        assertFalse(XiaoAiVoiceInputPolicy.canClaim("dictation", 23, false, false))
    }

    @Test
    fun voiceInputPolicyLeavesImageAndDocumentRoundsNative() {
        assertFalse(XiaoAiVoiceInputPolicy.canClaim("main", 23, true, false))
        assertFalse(XiaoAiVoiceInputPolicy.canClaim("main", 23, false, true))
        assertFalse(XiaoAiVoiceInputPolicy.canClaim("main", 23, true, true))
    }

    @Test
    fun actionOwnershipUsesItsOwnRidInsteadOfTheLatestTurn() {
        val state = XiaoAiVoiceTakeoverState()
        state.tryClaim("owned-A") { true }
        assertTrue(state.isClaimed("owned-A"))
        assertFalse(state.isClaimed("unclaimed-B"))
        assertFalse(state.isClaimed(""))
        assertFalse(state.isClaimed(" "))
    }

    @Test
    fun partialAndEmptyResultsCannotBecomeFinalAsr() {
        assertNull(XiaoAiFinalAsr.from("dialog", false, listOf("partial")))
        assertNull(XiaoAiFinalAsr.from("dialog", true, emptyList()))
        assertNull(XiaoAiFinalAsr.from("dialog", true, listOf(" ")))
    }

    @Test
    fun finalAsrUsesTheFirstAlternativeLikeTheNativeProcessor() {
        assertEquals(
            XiaoAiFinalAsr("dialog", "first"),
            XiaoAiFinalAsr.from(" dialog ", true, listOf(" first ", "second")),
        )
    }

    @Test
    fun anUnclaimedDialogKeepsNativeBehavior() {
        val state = XiaoAiVoiceTakeoverState()
        assertFalse(state.shouldSuppress("dialog", "Template.ToastStream"))
        assertFalse(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
    }

    @Test
    fun blankDialogNeverStartsARun() {
        val state = XiaoAiVoiceTakeoverState()
        assertFalse(state.tryClaim(" ") { error("must not start") })
    }

    @Test
    fun rejectedLaunchDoesNotSuppressNativeAnswerAndCanBeRetried() {
        val state = XiaoAiVoiceTakeoverState()
        assertFalse(state.tryClaim("dialog") { false })
        assertFalse(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
        assertTrue(state.tryClaim("dialog") { true })
        assertTrue(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
    }

    @Test
    fun throwingLaunchDoesNotLeaveAClaim() {
        val state = XiaoAiVoiceTakeoverState()
        runCatching { state.tryClaim("dialog") { error("launch failed") } }
        assertFalse(state.shouldSuppress("dialog", "Template.ToastStream"))
        assertTrue(state.tryClaim("dialog") { true })
    }

    @Test
    fun duplicateFinalAsrStartsExactlyOneRun() {
        val state = XiaoAiVoiceTakeoverState()
        var starts = 0
        repeat(3) {
            assertTrue(state.tryClaim(" dialog ") { starts++; true })
        }
        assertEquals(1, starts)
    }

    @Test
    fun concurrentFinalAsrStartsExactlyOneRun() {
        val state = XiaoAiVoiceTakeoverState()
        val starts = AtomicInteger()
        val workers = List(8) {
            Thread {
                state.tryClaim("dialog") { starts.incrementAndGet(); true }
            }
        }
        workers.forEach(Thread::start)
        workers.forEach(Thread::join)
        assertEquals(1, starts.get())
        assertTrue(state.isClaimed("dialog"))
    }

    @Test
    fun onlyTheClaimedDialogIsSuppressed() {
        val state = XiaoAiVoiceTakeoverState()
        state.tryClaim("current-round") { true }
        assertTrue(state.shouldSuppress("current-round", "SpeechSynthesizer.Speak"))
        assertFalse(state.shouldSuppress("next-round", "SpeechSynthesizer.Speak"))
        assertFalse(state.shouldSuppress("", "SpeechSynthesizer.Speak"))
    }

    @Test
    fun independentDialogsWithIdenticalQueriesRemainSeparateRuns() {
        val state = XiaoAiVoiceTakeoverState()
        val first = XiaoAiFinalAsr.from("first", true, listOf("same query"))!!
        val second = XiaoAiFinalAsr.from("second", true, listOf("same query"))!!
        var starts = 0
        assertTrue(state.tryClaim(first.dialogId) { starts++; true })
        assertFalse(state.isClaimed(second.dialogId))
        assertTrue(state.tryClaim(second.dialogId) { starts++; true })
        assertEquals(2, starts)
    }

    @Test
    fun claimedDialogBlocksAllNativeResponsesAndOperations() {
        val state = XiaoAiVoiceTakeoverState()
        state.tryClaim("dialog") { true }
        listOf(
            "Nlp.StartPreStream",
            "Nlp.StartStream",
            "Nlp.FinishStream",
            "Template.Query",
            "Template.LLMLoadingCard",
            "Template.FrontendPage",
            "Template.ToastStream",
            "SpeechSynthesizer.Speak",
            "SpeechSynthesizer.SpeakStream",
            "Suggestion.ShowContextSuggestions",
            "Agent.Execute",
            "Device.SetVolume",
            "Dialog.Finish",
            "System.Abort",
            "Unknown.NewOperation",
        ).forEach { fullName ->
            assertTrue(fullName, state.shouldSuppress("dialog", fullName))
        }
    }

    @Test
    fun captureAndRoundRolloverControlInstructionsStillReachNativeCode() {
        val state = XiaoAiVoiceTakeoverState()
        state.tryClaim("dialog") { true }
        listOf(
            "SpeechRecognizer.RecognizeResult",
            "SpeechRecognizer.StopCapture",
            "System.TruncationNotification",
            "System.Heartbeat",
            "Offline.CloudStop",
        ).forEach { fullName ->
            assertFalse(fullName, state.shouldSuppress("dialog", fullName))
        }
    }

    @Test
    fun dialogFinishDoesNotReleaseTheTombstoneOrAllowLateAudio() {
        val state = XiaoAiVoiceTakeoverState()
        state.tryClaim("dialog") { true }
        assertTrue(state.shouldSuppress("dialog", "Dialog.Finish"))
        assertTrue(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
        assertTrue(state.tryClaim("dialog") { error("must not relaunch") })
    }

    @Test
    fun tombstonesExpireWithoutBeingExtendedByDuplicateAsr() {
        var now = 0L
        val state = XiaoAiVoiceTakeoverState(ttlMillis = 100L, clock = { now })
        state.tryClaim("dialog") { true }
        now = 90L
        state.tryClaim("dialog") { error("must not relaunch") }
        now = 101L
        assertFalse(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
        assertTrue(state.tryClaim("dialog") { true })
    }

    @Test
    fun tombstonesAreBounded() {
        val state = XiaoAiVoiceTakeoverState(capacity = 2)
        state.tryClaim("first") { true }
        state.tryClaim("second") { true }
        state.tryClaim("third") { true }
        assertFalse(state.shouldSuppress("first", "Template.ToastStream"))
        assertTrue(state.shouldSuppress("second", "Template.ToastStream"))
        assertTrue(state.shouldSuppress("third", "Template.ToastStream"))
    }

    @Test
    fun etaOutputBypassesSuppressionOnlyInsideItsScopedCall() {
        val state = XiaoAiVoiceTakeoverState()
        state.tryClaim("dialog") { true }
        XiaoAiEtaOutput.run {
            assertFalse(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
            XiaoAiEtaOutput.run {
                assertFalse(state.shouldSuppress("dialog", "Template.ToastStream"))
            }
            assertTrue(XiaoAiEtaOutput.isActive())
        }
        assertFalse(XiaoAiEtaOutput.isActive())
        assertTrue(state.shouldSuppress("dialog", "SpeechSynthesizer.Speak"))
    }

    @Test
    fun etaOutputScopeIsRestoredAfterFailureAndNeverLeaksToOtherThreads() {
        val otherThreadIsBypassed = AtomicBoolean(true)
        runCatching {
            XiaoAiEtaOutput.run {
                val worker = Thread {
                    otherThreadIsBypassed.set(XiaoAiEtaOutput.isActive())
                }
                worker.start()
                worker.join()
                error("output failed")
            }
        }
        assertFalse(otherThreadIsBypassed.get())
        assertFalse(XiaoAiEtaOutput.isActive())
    }
}
