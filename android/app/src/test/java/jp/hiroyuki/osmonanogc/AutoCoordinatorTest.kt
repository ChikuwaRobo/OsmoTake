package jp.hiroyuki.osmonanogc

import org.junit.Assert.*
import org.junit.Test

class AutoCoordinatorTest {
    @Test fun onlyFreshRunningStateStarts() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        assertNull(subject.onMatchState(true))
        assertEquals(AutoCoordinator.Command.START, subject.onBleReady())
        assertNull(subject.onMatchState(true)) // no command storm
    }

    @Test fun nullMessagesAreRepresentedByNoCallAndPreserveIntent() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        subject.onMatchState(true)
        assertEquals(true, subject.desiredRecording)
        assertNull(subject.onBleReady())
        assertEquals(true, subject.desiredRecording)
    }

    @Test fun nonRunningCancelsPendingStartWithStop() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.onMatchState(true))
        assertEquals(AutoCoordinator.Command.STOP, subject.onMatchState(false))
    }

    @Test fun reconnectRequiresFreshStateAndGraceStops() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        subject.onMatchState(true)
        subject.onGcDisconnected()
        subject.onGcConnected()
        assertNull(subject.onBleReady())
        assertEquals(AutoCoordinator.Command.STOP, subject.onDisconnectGraceExpired())
    }

    @Test fun freshReconnectStateCancelsGraceDecision() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        subject.onMatchState(true)
        subject.onGcDisconnected()
        subject.onGcConnected()
        subject.onMatchState(true)
        assertNull(subject.onDisconnectGraceExpired())
    }

    @Test fun manualStopDisablesAutomaticMode() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.STOP, subject.manualStop())
        assertFalse(subject.autoEnabled)
    }

    @Test fun oppositePendingCommandIsActivelySuperseded() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        subject.onConfirmed(RecordingState.STOPPED)
        assertEquals(AutoCoordinator.Command.START, subject.onMatchState(true))
        assertEquals(AutoCoordinator.Command.STOP, subject.onMatchState(false))
    }

    @Test fun manualStopSurvivesBleReconnect() {
        val subject = AutoCoordinator()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.STOP, subject.manualStop())
        subject.onBleLost()
        assertEquals(AutoCoordinator.Command.STOP, subject.onBleReady())
    }

    @Test fun graceStopSurvivesBleReconnect() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onBleReady()
        subject.onGcConnected()
        subject.onGcDisconnected()
        subject.onBleLost()
        assertNull(subject.onDisconnectGraceExpired())
        subject.onGcConnected() // socket open alone is not a fresh state
        assertEquals(AutoCoordinator.Command.STOP, subject.onBleReady())
    }

    @Test fun timeoutDoesNotStormButBleReconnectRetriesIntent() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.onMatchState(true))
        subject.onCommandTimeout(AutoCoordinator.Command.START)
        assertNull(subject.onConfirmed(RecordingState.STOPPED))
        subject.onBleLost()
        assertEquals(AutoCoordinator.Command.START, subject.onBleReady())
    }

    @Test fun disabledAutoNeverResurrectsOldStartOnBleReconnect() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onGcConnected()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.onMatchState(true))
        subject.setAuto(false)
        subject.onBleLost()
        assertNull(subject.onBleReady())
        assertFalse(subject.autoEnabled)
    }

    @Test fun reenablingAutoWithFreshRunningClearsOldSafetyStopLatch() {
        val subject = AutoCoordinator()
        subject.onGcConnected()
        subject.onBleReady()
        subject.onMatchState(true) // remembered while auto is off
        subject.manualStop()
        assertEquals(AutoCoordinator.Command.START, subject.setAuto(true))
        subject.onBleLost()
        assertEquals(AutoCoordinator.Command.START, subject.onBleReady())
    }

    @Test fun manualStartWhileReadyStartsAndDisablesAuto() {
        val subject = AutoCoordinator()
        subject.setAuto(true)
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.manualStart())
        assertFalse(subject.autoEnabled)
    }

    @Test fun manualStartOfflineIsNotDelayedUntilReconnect() {
        val subject = AutoCoordinator()
        assertNull(subject.manualStart())
        assertNull(subject.onBleReady())
        assertFalse(subject.autoEnabled)
    }

    @Test fun gcStateCannotOverrideManualStartWhileAutoIsOff() {
        val subject = AutoCoordinator()
        subject.onGcConnected()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.manualStart())
        assertNull(subject.onMatchState(false))
        assertEquals(true, subject.desiredRecording)
        subject.onGcDisconnected()
        assertEquals(AutoCoordinator.Command.START, subject.pending)
    }

    @Test fun bleLossDoesNotRestartManualStart() {
        val subject = AutoCoordinator()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.manualStart())
        subject.onBleLost()
        assertNull(subject.onBleReady())
    }

    @Test fun manualStopSupersedesPendingManualStart() {
        val subject = AutoCoordinator()
        subject.onBleReady()
        assertEquals(AutoCoordinator.Command.START, subject.manualStart())
        assertEquals(AutoCoordinator.Command.STOP, subject.manualStop())
        assertEquals(AutoCoordinator.Command.STOP, subject.pending)
    }
}
