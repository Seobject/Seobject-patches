package app.morphe.extension.discord.bunny.recovery;

/** Pure state machine for crash-loop detection and temporary Safe Mode. */
final class StartupRecoveryPolicy {
    private StartupRecoveryPolicy() {}

    static boolean begin(RecoveryState state) {
        if (state.startupInProgress && !state.startupHealthy) {
            state.consecutiveFailures++;
            state.recoveryLatch = true;
        }
        boolean retry = state.tryNormalOnce;
        boolean safeMode = !retry && (state.temporarySafeModeNextLaunch || state.recoveryLatch);
        // Consume before plugin initialization. Current-session state is held by
        // RecoveryManager and survives for the lifetime of this process.
        state.temporarySafeModeNextLaunch = false;
        state.tryNormalOnce = false;
        state.startupInProgress = false;
        state.startupHealthy = false;
        state.session++;
        return safeMode;
    }

    static void pluginStageStarted(RecoveryState state) {
        state.startupInProgress = true;
        state.startupHealthy = false;
    }

    static void healthy(RecoveryState state, boolean safeMode) {
        state.startupInProgress = false;
        state.startupHealthy = true;
        state.currentPlugin = null;
        state.initializingPlugins.clear();
        state.lastHealthyAt = System.currentTimeMillis();
        if (!safeMode) {
            state.consecutiveFailures = 0;
            state.recoveryLatch = false;
        }
    }
}
