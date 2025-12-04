package com.beanbeanjuice.simpleproxychat.utility.status;

import lombok.Getter;

import java.util.Optional;

/**
 * Tracks the status of a single backend server with debouncing to prevent false
 * positives.
 */
public class ServerStatus {

    @Getter
    private ServerState state = ServerState.UNKNOWN;
    private ServerState previousPingResult = null;
    private int onlineCount = 0;
    private int offlineCount = 0;

    private final int countUntilUpdate;

    /**
     * Creates a ServerStatus with default debounce threshold (5).
     */
    public ServerStatus() {
        this(5);
    }

    /**
     * Creates a ServerStatus with a custom debounce threshold.
     * 
     * @param countUntilUpdate Number of consecutive pings required to confirm a
     *                         state change.
     */
    public ServerStatus(int countUntilUpdate) {
        this.countUntilUpdate = Math.max(1, countUntilUpdate);
    }

    /**
     * Creates a ServerStatus with initial state and custom threshold.
     * 
     * @param initialState     The initial state of the server.
     * @param countUntilUpdate Number of consecutive pings required to confirm a
     *                         state change.
     */
    public ServerStatus(ServerState initialState, int countUntilUpdate) {
        this.state = initialState;
        this.countUntilUpdate = Math.max(1, countUntilUpdate);
    }

    private void resetCount() {
        onlineCount = 0;
        offlineCount = 0;
    }

    /**
     * Updates the status based on a ping result.
     * 
     * @param isOnline Whether the ping succeeded (server is online).
     * @return Optional containing the new state if a state change was confirmed,
     *         empty otherwise.
     */
    public Optional<ServerState> updateStatus(boolean isOnline) {
        ServerState targetState = isOnline ? ServerState.ONLINE : ServerState.OFFLINE;

        // If we're already in this state, no change needed
        if (targetState == this.state) {
            resetCount();
            return Optional.empty();
        }

        // Check if ping result direction changed (was trending online, now offline or
        // vice versa)
        ServerState currentPingResult = isOnline ? ServerState.ONLINE : ServerState.OFFLINE;
        if (previousPingResult != null && currentPingResult != previousPingResult) {
            resetCount();
        }
        previousPingResult = currentPingResult;

        // Increment appropriate counter
        int count = isOnline ? ++this.onlineCount : ++this.offlineCount;

        // Check if threshold reached
        if (count < countUntilUpdate) {
            return Optional.empty();
        }

        // Threshold reached - confirm state change
        resetCount();
        this.state = targetState;
        return Optional.of(this.state);
    }

    /**
     * Immediately confirms the server as online (for event-driven detection).
     * This bypasses the debounce threshold.
     * 
     * @return Optional containing ONLINE if state changed, empty if already online.
     */
    public Optional<ServerState> confirmOnline() {
        if (this.state == ServerState.ONLINE) {
            return Optional.empty();
        }

        resetCount();
        previousPingResult = ServerState.ONLINE;
        this.state = ServerState.ONLINE;
        return Optional.of(ServerState.ONLINE);
    }

    /**
     * Gets the status as a boolean for backwards compatibility.
     * 
     * @return true if ONLINE, false if OFFLINE or UNKNOWN.
     */
    public Boolean getStatus() {
        return this.state == ServerState.ONLINE;
    }

    /**
     * Checks if the server has ever been confirmed online or offline.
     * 
     * @return true if state is known (ONLINE or OFFLINE), false if UNKNOWN.
     */
    public boolean isKnown() {
        return this.state != ServerState.UNKNOWN;
    }
}
