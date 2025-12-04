package com.beanbeanjuice.simpleproxychat.utility;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages banned players using UUIDs for reliable identification.
 * Thread-safe implementation using ConcurrentHashMap.
 */
public class BanHelper {

    private static final Logger LOGGER = Logger.getLogger(BanHelper.class.getName());

    private YamlDocument yamlBans;
    private final File configFolder;

    // Thread-safe map: UUID -> last known username (for display purposes)
    private final ConcurrentHashMap<UUID, String> bannedPlayers = new ConcurrentHashMap<>();

    // Optional logger for async operations
    private Consumer<String> pluginLogger;

    public BanHelper(File configFolder) {
        this.configFolder = configFolder;
    }

    /**
     * Sets a logger for async operations.
     */
    public void setLogger(Consumer<String> logger) {
        this.pluginLogger = logger;
    }

    private void log(String message) {
        if (pluginLogger != null) {
            pluginLogger.accept(message);
        } else {
            LOGGER.info(message);
        }
    }

    private void logError(String message, Throwable e) {
        if (pluginLogger != null) {
            pluginLogger.accept(message + ": " + e.getMessage());
        } else {
            LOGGER.log(Level.WARNING, message, e);
        }
    }

    public void initialize() {
        try {
            yamlBans = loadBans("bannedPlayers.yml");
            yamlBans.update();
            yamlBans.save();
            readBans();
        } catch (IOException e) {
            logError("Failed to initialize ban list", e);
        }
    }

    public void reload() {
        try {
            yamlBans.reload();
            bannedPlayers.clear();
            readBans();
        } catch (IOException e) {
            logError("Failed to reload ban list", e);
        }
    }

    /**
     * Bans a player by UUID.
     * 
     * @param playerUUID The player's UUID
     * @param playerName The player's current username (for display/logging)
     */
    public void addBan(UUID playerUUID, String playerName) {
        if (playerUUID == null) {
            log("Cannot ban player: UUID is null for " + playerName);
            return;
        }

        bannedPlayers.put(playerUUID, playerName != null ? playerName : "Unknown");
        saveBans();
    }

    /**
     * Unbans a player by UUID.
     * 
     * @param playerUUID The player's UUID
     */
    public void removeBan(UUID playerUUID) {
        if (playerUUID == null)
            return;

        bannedPlayers.remove(playerUUID);
        saveBans();
    }

    /**
     * Unbans a player by username (searches for matching UUID).
     * 
     * @param playerName The player's username
     * @return true if a matching player was found and unbanned
     */
    public boolean removeBanByName(String playerName) {
        if (playerName == null || playerName.isBlank())
            return false;

        Optional<UUID> matchingUUID = bannedPlayers.entrySet().stream()
                .filter(e -> e.getValue().equalsIgnoreCase(playerName))
                .map(Map.Entry::getKey)
                .findFirst();

        if (matchingUUID.isPresent()) {
            bannedPlayers.remove(matchingUUID.get());
            saveBans();
            return true;
        }
        return false;
    }

    /**
     * Checks if a player is banned by UUID.
     * 
     * @param playerUUID The player's UUID
     * @return true if banned
     */
    public boolean isBanned(UUID playerUUID) {
        if (playerUUID == null)
            return false;
        return bannedPlayers.containsKey(playerUUID);
    }

    /**
     * @deprecated Use {@link #isBanned(UUID)} instead for security.
     *             This method is kept for backwards compatibility but is less
     *             secure.
     */
    @Deprecated
    public boolean isBannedByName(String playerName) {
        if (playerName == null || playerName.isBlank())
            return false;
        return bannedPlayers.values().stream()
                .anyMatch(name -> name.equalsIgnoreCase(playerName));
    }

    /**
     * Returns a list of banned player display names for tab completion.
     * 
     * @return List of banned player usernames
     */
    public List<String> getBannedPlayerNames() {
        return new ArrayList<>(bannedPlayers.values());
    }

    /**
     * Returns the map of banned players (UUID -> username).
     * 
     * @return Unmodifiable view of banned players
     */
    public Map<UUID, String> getBannedPlayers() {
        return Collections.unmodifiableMap(bannedPlayers);
    }

    private void readBans() {
        try {
            // Read from new format: bannedPlayers as a map of UUID -> username
            if (yamlBans.contains("bannedPlayersV2")) {
                var section = yamlBans.getSection("bannedPlayersV2");
                if (section != null) {
                    for (Object key : section.getKeys()) {
                        try {
                            UUID uuid = UUID.fromString(key.toString());
                            String name = section.getString(key.toString());
                            bannedPlayers.put(uuid, name != null ? name : "Unknown");
                        } catch (IllegalArgumentException ignored) {
                            // Skip invalid UUIDs
                        }
                    }
                }
            }

            // Legacy migration: read old username-only format and log a warning
            if (yamlBans.contains("bannedPlayers") && bannedPlayers.isEmpty()) {
                List<String> legacyBans = yamlBans.getStringList("bannedPlayers");
                if (legacyBans != null && !legacyBans.isEmpty()
                        && !(legacyBans.size() == 1 && legacyBans.get(0).equals("exampleBannedPlayerName"))) {
                    log("[WARNING] Found legacy username-based bans. These will need to be re-added with UUIDs.");
                    log("[WARNING] Legacy banned usernames: " + String.join(", ", legacyBans));
                }
            }
        } catch (Exception e) {
            logError("Failed to read ban list", e);
        }
    }

    private void saveBans() {
        try {
            // Save in new format
            Map<String, String> toSave = new HashMap<>();
            bannedPlayers.forEach((uuid, name) -> toSave.put(uuid.toString(), name));
            yamlBans.set("bannedPlayersV2", toSave);
            yamlBans.save();
        } catch (IOException e) {
            logError("Failed to save ban list", e);
        }
    }

    private YamlDocument loadBans(String fileName) throws IOException {
        return YamlDocument.create(
                new File(configFolder, fileName),
                Objects.requireNonNull(getClass().getResourceAsStream("/" + fileName)),
                GeneralSettings.DEFAULT,
                DumperSettings.DEFAULT);
    }
}
