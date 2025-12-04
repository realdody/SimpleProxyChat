package com.beanbeanjuice.simpleproxychat.utility.config;

import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.helper.ServerChatLockHelper;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Main configuration manager for SimpleProxyChat.
 * Handles loading and reloading of config.yml, messages.yml, and filter.yml.
 */
public class Config {
    
    private static final Logger LOGGER = Logger.getLogger(Config.class.getName());

    private YamlDocument yamlConfig;
    private YamlDocument yamlMessages;
    private YamlDocument yamlFilter;
    private final File configFolder;
    private final Map<ConfigKey, ConfigValue<?>> configCache;
    private final List<Runnable> reloadListeners;

    @Getter private final FilterConfig filterConfig;
    @Getter private final ServerChatLockHelper serverChatLockHelper;

    public Config(File configFolder) {
        this.configFolder = configFolder;
        this.configCache = new HashMap<>();
        this.reloadListeners = new ArrayList<>();
        this.filterConfig = new FilterConfig();
        this.serverChatLockHelper = new ServerChatLockHelper();
    }

    /**
     * Initializes the configuration by loading all config files.
     */
    public void initialize() {
        try {
            yamlConfig = loadConfigFile("config.yml");
            yamlMessages = loadConfigFile("messages.yml");
            yamlFilter = loadConfigFile("filter.yml");
            
            // Update and save files to ensure they're up-to-date
            updateAndSave(yamlConfig);
            updateAndSave(yamlMessages);
            updateAndSave(yamlFilter);
            
            // Load configuration values
            loadAllConfigs();
            
            LOGGER.info("Configuration loaded successfully");
        } catch (IOException e) {
            LOGGER.severe("Failed to initialize configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Adds a listener to be notified when configuration is reloaded.
     * @param listener The listener to add
     */
    public void addReloadListener(Runnable listener) {
        reloadListeners.add(listener);
    }

    /**
     * Reloads all configuration from disk and notifies listeners.
     */
    public void reload() {
        try {
            yamlConfig.reload();
            yamlMessages.reload();
            yamlFilter.reload();
            
            loadAllConfigs();
            
            reloadListeners.forEach(Runnable::run);
            
            LOGGER.info("Configuration reloaded successfully");
        } catch (IOException e) {
            LOGGER.severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Gets a configuration value by key.
     * @param key The configuration key
     * @return The configuration value wrapper (never null, but may contain null value)
     */
    public ConfigValueWrapper get(ConfigKey key) {
        ConfigValue<?> value = configCache.get(key);
        if (value == null) {
            LOGGER.warning("Requested config key not found: " + key);
            return new ConfigValueWrapper(null);
        }
        // Convert to old wrapper for backward compatibility
        return new ConfigValueWrapper(value.get());
    }

    /**
     * Loads all configuration values from YAML documents into cache.
     */
    private void loadAllConfigs() {
        loadMainConfigs();
        filterConfig.load(yamlFilter);
    }
    
    /**
     * Loads config.yml and messages.yml values using the ConfigLoader utility.
     */
    private void loadMainConfigs() {
        Arrays.stream(ConfigKey.values()).forEach(key -> {
            YamlDocument document = (key.getFile() == ConfigFileType.CONFIG) ? yamlConfig : yamlMessages;
            String path = key.getKey();
            Class<?> type = key.getClassType();
            
            ConfigValue<?> value = ConfigLoader.loadValue(document, path, type);
            configCache.put(key, value);
        });
    }

    /**
     * Overwrites a configuration value in the cache (runtime only, not persisted).
     * @param key The configuration key
     * @param value The new value
     */
    @SuppressWarnings("unchecked")
    public void overwrite(ConfigKey key, Object value) {
        configCache.put(key, new ConfigValue(value, key.getClassType()));
    }
    
    /**
     * Updates and saves a YAML document.
     */
    private void updateAndSave(YamlDocument document) throws IOException {
        document.update();
        document.save();
    }

    /**
     * Loads a configuration file from the config folder with versioning and auto-update.
     * @param fileName The name of the config file
     * @return The loaded YAML document
     * @throws IOException If file loading fails
     */
    private YamlDocument loadConfigFile(String fileName) throws IOException {
        return YamlDocument.create(
                new File(configFolder, fileName),
                Objects.requireNonNull(getClass().getResourceAsStream("/" + fileName)),
                GeneralSettings.DEFAULT,
                LoaderSettings.builder().setAutoUpdate(true).build(),
                DumperSettings.DEFAULT,
                UpdaterSettings.builder()
                        .setVersioning(new BasicVersioning("file-version"))
                        .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS)

                        .addRelocation("7", "minecraft.join.use", "minecraft.join.enabled", '.')
                        .addRelocation("7", "minecraft.leave.use", "minecraft.leave.enabled", '.')
                        .addRelocation("7", "minecraft.message", "minecraft.chat.message", '.')
                        .addRelocation("7", "minecraft.switch.use", "minecraft.switch.enabled", '.')
                        .addRelocation("7", "discord.join.use", "discord.join.enabled", '.')
                        .addRelocation("7", "discord.leave.use", "discord.leave.enabled", '.')
                        .addRelocation("7", "discord.switch.use", "discord.switch.enabled", '.')
                        .addRelocation("7", "discord.minecraft-message", "discord.chat.minecraft-message", '.')

                        .addRelocation("7", "discord.proxy-status.enabled", "discord.proxy-status.messages.enabled", '.')
                        .addRelocation("7", "discord.proxy-status.disabled", "discord.proxy-status.messages.disabled", '.')
                        .addRelocation("7", "discord.proxy-status.title", "discord.proxy-status.messages.title", '.')
                        .addRelocation("7", "discord.proxy-status.message", "discord.proxy-status.messages.message", '.')
                        .addRelocation("7", "discord.proxy-status.online", "discord.proxy-status.messages.online", '.')
                        .addRelocation("7", "discord.proxy-status.offline", "discord.proxy-status.messages.offline", '.')
                        .addRelocation("7", "discord.proxy-status.use-timestamp", "discord.proxy-status.messages.use-timestamp", '.')

                        .build()
        );
    }

    /**
     * Returns the alias for a server name for events webhook.
     * Uses the simple 'aliases' mapping (server -> alias) with legacy nested fallback.
     * @param serverName The server name to look up
     * @return The alias, or null if not found
     */
    public String getEventWebhookAliasOverride(String serverName) {
        if (yamlConfig == null || serverName == null || serverName.isBlank()) {
            return null;
        }
        
        Section aliases = yamlConfig.getSection("aliases");
        if (aliases == null) {
            return null;
        }

        // Try simple mapping first
        String simple = aliases.getString(serverName);
        if (simple != null && !simple.isBlank()) {
            return Helper.translateLegacyCodes(simple);
        }

        // Legacy fallback: nested mapping (server -> { Alias: AvatarURL })
        Section nested = aliases.getSection(serverName);
        if (nested != null) {
            for (Object child : nested.getKeys()) {
                String aliasKey = String.valueOf(child);
                if (aliasKey != null && !aliasKey.isBlank()) {
                    return Helper.translateLegacyCodes(aliasKey);
                }
            }
        }
        
        return null;
    }

    /**
     * Returns the avatar URL override for events webhook.
     * Looks up in 'alias-avatars' map (alias -> avatarUrl) with legacy nested fallback.
     * @param serverName The server name to look up
     * @return The avatar URL, or null if not found
     */
    public String getEventWebhookAvatarOverride(String serverName) {
        if (yamlConfig == null || serverName == null || serverName.isBlank()) {
            return null;
        }

        // Resolve alias first
        String alias = getEventWebhookAliasOverride(serverName);

        // Preferred: look up by alias in alias-avatars
        Section avatars = yamlConfig.getSection("alias-avatars");
        if (avatars != null && alias != null && !alias.isBlank()) {
            String url = avatars.getString(alias);
            if (url != null && !url.isBlank()) {
                return url;
            }
        }

        // Legacy fallback: nested mapping under aliases
        Section aliases = yamlConfig.getSection("aliases");
        if (aliases != null) {
            Section nested = aliases.getSection(serverName);
            if (nested != null) {
                for (Object child : nested.getKeys()) {
                    String aliasKey = String.valueOf(child);
                    String url = nested.getString(aliasKey);
                    if (url != null && !url.isBlank()) {
                        return url;
                    }
                    break;
                }
            }
        }
        
        return null;
    }
    
    // Deprecated methods for backward compatibility with filter access
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public boolean isFilterEnabled() { return filterConfig.isEnabled(); }
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public boolean isFilterCaseInsensitive() { return filterConfig.isCaseInsensitive(); }
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public boolean isFilterWholeWord() { return filterConfig.isWholeWord(); }
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public String getFilterDefaultReplacement() { return filterConfig.getDefaultReplacement(); }
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public Map<String, String> getFilterReplacements() { return filterConfig.getReplacements(); }
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public List<String> getFilterGlobalWords() { return filterConfig.getGlobalWords(); }
    
    /** @deprecated Use {@link #getFilterConfig()} instead */
    @Deprecated
    public List<FilterConfig.FilterRegexRule> getFilterRegexRules() { return filterConfig.getRegexRules(); }
}
