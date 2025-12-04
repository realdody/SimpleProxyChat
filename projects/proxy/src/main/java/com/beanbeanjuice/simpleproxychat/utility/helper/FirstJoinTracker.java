package com.beanbeanjuice.simpleproxychat.utility.helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Tracks player UUIDs to determine first-time joins.
 * Persists data to a JSON file in the config folder.
 */
public class FirstJoinTracker {

    private static final Logger LOGGER = Logger.getLogger(FirstJoinTracker.class.getName());
    private static final String FILE_NAME = "first-joins.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File dataFile;
    private final Set<UUID> knownPlayers;

    public FirstJoinTracker(File configFolder) {
        this.dataFile = new File(configFolder, FILE_NAME);
        this.knownPlayers = loadFromFile();
    }

    /**
     * Checks if this is the player's first time joining.
     * If it is, adds them to the known players list and saves.
     * 
     * @param playerUUID The player's UUID
     * @return true if this is their first join, false otherwise
     */
    public boolean isFirstJoin(UUID playerUUID) {
        if (knownPlayers.contains(playerUUID)) {
            return false;
        }
        
        // First time! Add and save
        knownPlayers.add(playerUUID);
        saveToFile();
        return true;
    }

    /**
     * Checks if a player has joined before without modifying state.
     */
    public boolean hasJoinedBefore(UUID playerUUID) {
        return knownPlayers.contains(playerUUID);
    }

    /**
     * Gets the total number of unique players who have joined.
     */
    public int getTotalUniquePlayers() {
        return knownPlayers.size();
    }

    private Set<UUID> loadFromFile() {
        if (!dataFile.exists()) {
            return new HashSet<>();
        }

        try (Reader reader = new FileReader(dataFile)) {
            Type setType = new TypeToken<Set<UUID>>(){}.getType();
            Set<UUID> loaded = GSON.fromJson(reader, setType);
            return loaded != null ? new HashSet<>(loaded) : new HashSet<>();
        } catch (Exception e) {
            LOGGER.warning("Failed to load first-joins data: " + e.getMessage());
            return new HashSet<>();
        }
    }

    private void saveToFile() {
        try {
            // Ensure parent directory exists
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }

            try (Writer writer = new FileWriter(dataFile)) {
                GSON.toJson(knownPlayers, writer);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to save first-joins data: " + e.getMessage());
        }
    }
}
