package com.beanbeanjuice.simpleproxychat.test.status;

import com.beanbeanjuice.simpleproxychat.utility.status.ServerState;
import com.beanbeanjuice.simpleproxychat.utility.status.ServerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class ServerStatusTest {

    @Test
    @DisplayName("Initial state should be UNKNOWN")
    public void testInitialStateIsUnknown() {
        ServerStatus status = new ServerStatus();
        assertEquals(ServerState.UNKNOWN, status.getState());
        assertFalse(status.isKnown());
    }

    @Test
    @DisplayName("Should require N consecutive online pings to transition to ONLINE")
    public void testDebounceToOnline() {
        int threshold = 3;
        ServerStatus status = new ServerStatus(threshold);

        // First 2 pings should not trigger state change
        for (int i = 0; i < threshold - 1; i++) {
            Optional<ServerState> result = status.updateStatus(true);
            assertTrue(result.isEmpty(), "Should not trigger state change at ping " + (i + 1));
            assertEquals(ServerState.UNKNOWN, status.getState());
        }

        // Third ping should confirm ONLINE
        Optional<ServerState> result = status.updateStatus(true);
        assertTrue(result.isPresent());
        assertEquals(ServerState.ONLINE, result.get());
        assertEquals(ServerState.ONLINE, status.getState());
        assertTrue(status.isKnown());
    }

    @Test
    @DisplayName("Should require N consecutive offline pings to transition to OFFLINE")
    public void testDebounceToOffline() {
        int threshold = 3;
        ServerStatus status = new ServerStatus(ServerState.ONLINE, threshold);
        assertEquals(ServerState.ONLINE, status.getState());

        // First 2 pings should not trigger state change
        for (int i = 0; i < threshold - 1; i++) {
            Optional<ServerState> result = status.updateStatus(false);
            assertTrue(result.isEmpty(), "Should not trigger state change at ping " + (i + 1));
            assertEquals(ServerState.ONLINE, status.getState());
        }

        // Third ping should confirm OFFLINE
        Optional<ServerState> result = status.updateStatus(false);
        assertTrue(result.isPresent());
        assertEquals(ServerState.OFFLINE, result.get());
        assertEquals(ServerState.OFFLINE, status.getState());
    }

    @Test
    @DisplayName("Counter should reset when status oscillates")
    public void testCounterResetsOnOscillation() {
        int threshold = 3;
        ServerStatus status = new ServerStatus(threshold);

        // Two online pings
        status.updateStatus(true);
        status.updateStatus(true);

        // One offline ping - should reset
        status.updateStatus(false);

        // Two more online pings - should not trigger (only 2 consecutive)
        status.updateStatus(true);
        Optional<ServerState> result = status.updateStatus(true);
        assertTrue(result.isEmpty(), "Should not trigger after oscillation");

        // Third consecutive online ping should trigger
        result = status.updateStatus(true);
        assertTrue(result.isPresent());
        assertEquals(ServerState.ONLINE, result.get());
    }

    @Test
    @DisplayName("confirmOnline should immediately set status to ONLINE")
    public void testConfirmOnlineInstant() {
        ServerStatus status = new ServerStatus();
        assertEquals(ServerState.UNKNOWN, status.getState());

        Optional<ServerState> result = status.confirmOnline();
        assertTrue(result.isPresent());
        assertEquals(ServerState.ONLINE, result.get());
        assertEquals(ServerState.ONLINE, status.getState());
    }

    @Test
    @DisplayName("confirmOnline should return empty if already ONLINE")
    public void testConfirmOnlineWhenAlreadyOnline() {
        ServerStatus status = new ServerStatus(ServerState.ONLINE, 5);
        assertEquals(ServerState.ONLINE, status.getState());

        Optional<ServerState> result = status.confirmOnline();
        assertTrue(result.isEmpty());
        assertEquals(ServerState.ONLINE, status.getState());
    }

    @Test
    @DisplayName("confirmOnline should work from OFFLINE state")
    public void testConfirmOnlineFromOffline() {
        ServerStatus status = new ServerStatus(ServerState.OFFLINE, 5);
        assertEquals(ServerState.OFFLINE, status.getState());

        Optional<ServerState> result = status.confirmOnline();
        assertTrue(result.isPresent());
        assertEquals(ServerState.ONLINE, result.get());
    }

    @Test
    @DisplayName("Configurable threshold should work correctly")
    public void testConfigurableThreshold() {
        // Test with threshold of 1 (instant)
        ServerStatus instant = new ServerStatus(1);
        Optional<ServerState> result = instant.updateStatus(true);
        assertTrue(result.isPresent());
        assertEquals(ServerState.ONLINE, result.get());

        // Test with threshold of 10
        ServerStatus slow = new ServerStatus(10);
        for (int i = 0; i < 9; i++) {
            assertTrue(slow.updateStatus(true).isEmpty());
        }
        result = slow.updateStatus(true);
        assertTrue(result.isPresent());
        assertEquals(ServerState.ONLINE, result.get());
    }

    @Test
    @DisplayName("getStatus should return true only for ONLINE state")
    public void testGetStatusBackwardsCompatibility() {
        ServerStatus unknown = new ServerStatus();
        assertFalse(unknown.getStatus());

        ServerStatus online = new ServerStatus(ServerState.ONLINE, 5);
        assertTrue(online.getStatus());

        ServerStatus offline = new ServerStatus(ServerState.OFFLINE, 5);
        assertFalse(offline.getStatus());
    }

    @Test
    @DisplayName("Should not change state if same status is pinged repeatedly")
    public void testNoChangeOnSameStatus() {
        ServerStatus status = new ServerStatus(ServerState.ONLINE, 5);

        // Multiple online pings when already online should return empty
        for (int i = 0; i < 10; i++) {
            Optional<ServerState> result = status.updateStatus(true);
            assertTrue(result.isEmpty(), "Should not trigger state change for same status");
        }
        assertEquals(ServerState.ONLINE, status.getState());
    }

    @Test
    @DisplayName("Minimum threshold should be 1")
    public void testMinimumThreshold() {
        // Threshold of 0 or negative should be treated as 1
        ServerStatus zeroThreshold = new ServerStatus(0);
        Optional<ServerState> result = zeroThreshold.updateStatus(true);
        assertTrue(result.isPresent(), "Zero threshold should behave as 1");

        ServerStatus negativeThreshold = new ServerStatus(-5);
        result = negativeThreshold.updateStatus(true);
        assertTrue(result.isPresent(), "Negative threshold should behave as 1");
    }
}
