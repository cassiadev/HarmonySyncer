package com.cassiadev.harmonysyncer.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeRecorderTest {
    private val builtIn = AudioDeviceInfo.TYPE_BUILTIN_MIC

    @Test
    fun `a silenced take is interrupted`() {
        assertTrue(shouldInterruptTake(silenced = true, inputType = builtIn, requiredInputType = builtIn))
    }

    @Test
    fun `leaving the required microphone interrupts the take`() {
        assertTrue(shouldInterruptTake(silenced = false, inputType = AudioDeviceInfo.TYPE_WIRED_HEADSET, requiredInputType = builtIn))
    }

    @Test
    fun `the required microphone or an unknown device goes on`() {
        assertFalse(shouldInterruptTake(silenced = false, inputType = builtIn, requiredInputType = builtIn))
        assertFalse(shouldInterruptTake(silenced = false, inputType = null, requiredInputType = builtIn))
    }

    @Test
    fun `without a required microphone any input goes on`() {
        assertFalse(shouldInterruptTake(silenced = false, inputType = AudioDeviceInfo.TYPE_WIRED_HEADSET, requiredInputType = null))
    }
}
