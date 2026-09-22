package jp.hiroyuki.osmonanogc

/** Pure state machine. All calls are serialized by ControlService. */
class AutoCoordinator {
    enum class Command { START, STOP }

    var autoEnabled = false
        private set
    var gcConnected = false
        private set
    var freshMatchState = false
        private set
    var desiredRecording: Boolean? = null
        private set
    var confirmed = RecordingState.UNKNOWN
        private set
    var pending: Command? = null
        private set
    var bleReady = false
        private set
    private var intentActive = false
    private var lastMatchRunning: Boolean? = null
    private var safetyStopLatched = false

    fun setAuto(enabled: Boolean): Command? {
        autoEnabled = enabled
        if (!enabled) {
            pending = null
            intentActive = false
            safetyStopLatched = false
            return null
        }
        if (gcConnected && freshMatchState && lastMatchRunning != null) {
            safetyStopLatched = false
            desiredRecording = lastMatchRunning
            intentActive = true
        }
        return reconcile()
    }

    fun manualStop(): Command {
        autoEnabled = false
        desiredRecording = false
        intentActive = true
        safetyStopLatched = true
        return request(Command.STOP) ?: Command.STOP
    }

    /** Manual START is immediate-only: never latch it while BLE is unavailable. */
    fun manualStart(): Command? {
        autoEnabled = false
        safetyStopLatched = false
        pending = null
        desiredRecording = true
        if (!bleReady) {
            intentActive = false
            return null
        }
        intentActive = true
        return request(Command.START)
    }

    fun onGcConnected() {
        gcConnected = true
        freshMatchState = false
        if (autoEnabled && !safetyStopLatched) {
            desiredRecording = null
            intentActive = false
            pending = null
        }
    }

    fun onGcDisconnected() {
        gcConnected = false
        freshMatchState = false
        if (autoEnabled && !safetyStopLatched) {
            desiredRecording = null
            intentActive = false
            pending = null
        }
    }

    fun onMatchState(running: Boolean): Command? {
        freshMatchState = true
        lastMatchRunning = running
        if (!autoEnabled) return null
        safetyStopLatched = false
        desiredRecording = running
        intentActive = true
        return reconcile()
    }

    fun onDisconnectGraceExpired(): Command? {
        if (!autoEnabled || freshMatchState) return null
        desiredRecording = false
        intentActive = true
        safetyStopLatched = true
        return request(Command.STOP)
    }

    fun onBleReady(): Command? {
        bleReady = true
        return reconcile()
    }

    fun onBleLost() {
        bleReady = false
        confirmed = RecordingState.UNKNOWN
        pending = null
        intentActive = safetyStopLatched ||
            (autoEnabled && gcConnected && freshMatchState && desiredRecording != null)
    }

    fun onConfirmed(state: RecordingState): Command? {
        confirmed = state
        val matched = (pending == Command.START && state == RecordingState.RECORDING) ||
            (pending == Command.STOP && state == RecordingState.STOPPED)
        if (matched) pending = null
        return reconcile()
    }

    fun onCommandTimeout(command: Command) {
        if (pending == command) {
            pending = null
            intentActive = false
        }
    }

    private fun reconcile(): Command? {
        if (!intentActive || !bleReady) return null
        return when (desiredRecording) {
            true -> if (confirmed == RecordingState.RECORDING && pending != Command.STOP) null else request(Command.START)
            false -> if (confirmed == RecordingState.STOPPED && pending != Command.START) null else request(Command.STOP)
            null -> null
        }
    }

    private fun request(command: Command): Command? {
        if (!bleReady) return null
        if (pending == command) return null
        pending = command
        return command
    }
}
