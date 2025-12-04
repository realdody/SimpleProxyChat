package com.beanbeanjuice.simpleproxychat.utility.status;

/**
 * Represents the known state of a backend server.
 */
public enum ServerState {
    /**
     * Server has never been successfully pinged or confirmed online.
     */
    UNKNOWN,

    /**
     * Server is confirmed to be online.
     */
    ONLINE,

    /**
     * Server was previously online but is now confirmed offline.
     */
    OFFLINE
}
