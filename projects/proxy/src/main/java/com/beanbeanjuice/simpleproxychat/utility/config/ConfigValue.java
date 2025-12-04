package com.beanbeanjuice.simpleproxychat.utility.config;

import lombok.RequiredArgsConstructor;
import org.joda.time.DateTimeZone;

import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Type-safe wrapper for configuration values with proper generics support.
 * Immutable and null-safe by design.
 */
@RequiredArgsConstructor
public class ConfigValue<T> {
    
    private final T value;
    private final Class<T> type;
    
    /**
     * Gets the raw value.
     * @return The configuration value, may be null
     */
    public T get() {
        return value;
    }
    
    /**
     * Gets the value as an Optional.
     * @return Optional containing the value, or empty if null
     */
    public Optional<T> asOptional() {
        return Optional.ofNullable(value);
    }
    
    /**
     * Gets the value or returns a default.
     * @param defaultValue The default value to return if null
     * @return The value or default
     */
    public T orElse(T defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    /**
     * Gets the type of this config value.
     * @return The class type
     */
    public Class<T> getType() {
        return type;
    }
    
    // Convenience methods for common types with safe casting
    
    public String asString() {
        if (value instanceof String) {
            return (String) value;
        }
        throw new ClassCastException("Config value is not a String: " + (value != null ? value.getClass() : "null"));
    }
    
    public Integer asInt() {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        throw new ClassCastException("Config value is not an Integer: " + (value != null ? value.getClass() : "null"));
    }
    
    public Boolean asBoolean() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new ClassCastException("Config value is not a Boolean: " + (value != null ? value.getClass() : "null"));
    }
    
    public Color asColor() {
        if (value instanceof Color) {
            return (Color) value;
        }
        throw new ClassCastException("Config value is not a Color: " + (value != null ? value.getClass() : "null"));
    }
    
    @SuppressWarnings("unchecked")
    public Map<String, String> asStringMap() {
        if (value instanceof Map) {
            return (Map<String, String>) value;
        }
        throw new ClassCastException("Config value is not a Map: " + (value != null ? value.getClass() : "null"));
    }
    
    @SuppressWarnings("unchecked")
    public List<String> asList() {
        if (value instanceof List) {
            return (List<String>) value;
        }
        throw new ClassCastException("Config value is not a List: " + (value != null ? value.getClass() : "null"));
    }
    
    public DateTimeZone asDateTimeZone() {
        if (value instanceof DateTimeZone) {
            return (DateTimeZone) value;
        }
        throw new ClassCastException("Config value is not a DateTimeZone: " + (value != null ? value.getClass() : "null"));
    }
    
    @Override
    public String toString() {
        return "ConfigValue{" +
                "value=" + value +
                ", type=" + type.getSimpleName() +
                '}';
    }
}

