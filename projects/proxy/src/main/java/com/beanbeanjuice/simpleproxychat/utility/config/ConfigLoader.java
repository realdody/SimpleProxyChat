package com.beanbeanjuice.simpleproxychat.utility.config;

import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import org.joda.time.DateTimeZone;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/**
 * Utility class for loading and parsing configuration values from YAML documents.
 * Provides type-safe value extraction with proper error handling.
 */
public class ConfigLoader {
    
    private static final Logger LOGGER = Logger.getLogger(ConfigLoader.class.getName());
    
    // Registry of type parsers
    private static final Map<Class<?>, BiFunction<YamlDocument, String, ?>> TYPE_PARSERS = new HashMap<>();
    
    static {
        // Register default type parsers
        TYPE_PARSERS.put(String.class, ConfigLoader::loadString);
        TYPE_PARSERS.put(Integer.class, ConfigLoader::loadInteger);
        TYPE_PARSERS.put(Boolean.class, ConfigLoader::loadBoolean);
        TYPE_PARSERS.put(Color.class, ConfigLoader::loadColor);
        TYPE_PARSERS.put(DateTimeZone.class, ConfigLoader::loadDateTimeZone);
        TYPE_PARSERS.put(Map.class, ConfigLoader::loadMap);
        TYPE_PARSERS.put(List.class, ConfigLoader::loadList);
    }
    
    /**
     * Loads a configuration value of the specified type.
     * @param document The YAML document
     * @param path The configuration path
     * @param type The expected type
     * @param <T> The type parameter
     * @return The loaded value wrapped in ConfigValue
     */
    @SuppressWarnings("unchecked")
    public static <T> ConfigValue<T> loadValue(YamlDocument document, String path, Class<T> type) {
        BiFunction<YamlDocument, String, ?> parser = TYPE_PARSERS.get(type);
        
        if (parser == null) {
            LOGGER.warning("No parser registered for type: " + type.getName() + " at path: " + path);
            return new ConfigValue<>(null, type);
        }
        
        try {
            T value = (T) parser.apply(document, path);
            return new ConfigValue<>(value, type);
        } catch (Exception e) {
            LOGGER.warning("Error loading config value at path '" + path + "': " + e.getMessage());
            return new ConfigValue<>(null, type);
        }
    }
    
    /**
     * Loads a String value with legacy code translation.
     */
    private static String loadString(YamlDocument document, String path) {
        String value = document.getString(path);
        return value != null ? Helper.translateLegacyCodes(value) : null;
    }
    
    /**
     * Loads an Integer value.
     */
    private static Integer loadInteger(YamlDocument document, String path) {
        return document.getInt(path);
    }
    
    /**
     * Loads a Boolean value.
     */
    private static Boolean loadBoolean(YamlDocument document, String path) {
        return document.getBoolean(path);
    }
    
    /**
     * Loads a Color value from hex string.
     */
    private static Color loadColor(YamlDocument document, String path) {
        String colorString = document.getString(path);
        
        if (colorString == null || colorString.isEmpty()) {
            LOGGER.warning("Color at path '" + path + "' is null or empty, defaulting to black");
            return Color.BLACK;
        }
        
        try {
            return Color.decode(colorString);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid color '" + colorString + "' at path '" + path + "', defaulting to black");
            return Color.BLACK;
        }
    }
    
    /**
     * Loads a DateTimeZone value.
     */
    private static DateTimeZone loadDateTimeZone(YamlDocument document, String path) {
        String timezoneString = document.getString(path);
        
        if (timezoneString == null || timezoneString.isEmpty()) {
            LOGGER.warning("Timezone at path '" + path + "' is null or empty, using default");
            return DateTimeZone.forID("America/Los_Angeles");
        }
        
        try {
            return DateTimeZone.forID(timezoneString);
        } catch (IllegalArgumentException e) {
            LOGGER.warning("Invalid timezone '" + timezoneString + "' at path '" + path + "', using default. " +
                    "See https://www.joda.org/joda-time/timezones.html");
            return DateTimeZone.forID("America/Los_Angeles");
        }
    }
    
    /**
     * Loads a Map<String, String> value with legacy code translation.
     * Includes special handling for nested aliases structure.
     */
    private static Map<String, String> loadMap(YamlDocument document, String path) {
        Map<String, String> map = new HashMap<>();
        Section section = document.getSection(path);
        
        if (section == null) {
            return map;
        }
        
        for (Object rawKey : section.getKeys()) {
            String key = String.valueOf(rawKey);
            String value = section.getString(key);
            
            // Special handling for nested aliases structure (backward compatibility)
            if (value == null && "aliases".equals(path)) {
                Section nested = section.getSection(key);
                if (nested != null) {
                    // Use the first child key as the alias string
                    for (Object child : nested.getKeys()) {
                        value = String.valueOf(child);
                        break;
                    }
                }
            }
            
            if (value != null) {
                map.put(key, Helper.translateLegacyCodes(value));
            }
        }
        
        return map;
    }
    
    /**
     * Loads a List<String> value with legacy code translation.
     */
    private static List<String> loadList(YamlDocument document, String path) {
        List<String> list = document.getStringList(path);
        
        if (list == null) {
            return Collections.emptyList();
        }
        
        return list.stream()
                .map(Helper::translateLegacyCodes)
                .toList();
    }
}

