package com.beanbeanjuice.simpleproxychat.chat;

import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.epoch.EpochHelper;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized placeholder replacement logic for message formatting.
 * Reduces duplication across ChatHandler's message processing methods.
 */
public class MessageFormatter {

    private final Config config;

    public MessageFormatter(Config config) {
        this.config = config;
    }

    /**
     * Creates a builder for constructing placeholder replacements.
     * @return A new ReplacementBuilder instance
     */
    public ReplacementBuilder builder() {
        return new ReplacementBuilder(config);
    }

    /**
     * Gets the current formatted time string based on config settings.
     * @return Formatted time string
     */
    public String getTimeString() {
        DateTimeZone zone = config.get(ConfigKey.TIMESTAMP_TIMEZONE).asDateTimeZone();
        DateTimeFormatter format = DateTimeFormat.forPattern(config.get(ConfigKey.TIMESTAMP_FORMAT).asString());

        long timeInMillis = EpochHelper.getEpochMillisecond();
        DateTime time = new DateTime(timeInMillis).withZone(zone);

        return time.toString(format);
    }

    /**
     * Builder class for constructing replacement lists with common placeholders.
     */
    public static class ReplacementBuilder {
        private final Config config;
        private final List<Tuple<String, String>> replacements;

        private ReplacementBuilder(Config config) {
            this.config = config;
            this.replacements = new ArrayList<>();
        }

        /**
         * Adds player-related placeholders (%player%, %escaped_player%).
         */
        public ReplacementBuilder withPlayer(String playerName) {
            replacements.add(Tuple.of("player", playerName));
            replacements.add(Tuple.of("escaped_player", Helper.escapeString(playerName)));
            return this;
        }

        /**
         * Adds server-related placeholders (%server%, %original_server%, %to%, %original_to%).
         */
        public ReplacementBuilder withServer(String aliasedServerName, String originalServerName) {
            replacements.add(Tuple.of("server", aliasedServerName));
            replacements.add(Tuple.of("original_server", originalServerName));
            replacements.add(Tuple.of("to", aliasedServerName));
            replacements.add(Tuple.of("original_to", originalServerName));
            return this;
        }

        /**
         * Adds from-server placeholders for switch messages (%from%, %original_from%).
         */
        public ReplacementBuilder withFromServer(String aliasedFrom, String originalFrom) {
            replacements.add(Tuple.of("from", aliasedFrom));
            replacements.add(Tuple.of("original_from", originalFrom));
            return this;
        }

        /**
         * Adds message placeholder (%message%).
         */
        public ReplacementBuilder withMessage(String message) {
            replacements.add(Tuple.of("message", message));
            return this;
        }

        /**
         * Adds time-related placeholders (%epoch%, %time%).
         */
        public ReplacementBuilder withTime(String timeString) {
            replacements.add(Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())));
            replacements.add(Tuple.of("time", timeString));
            return this;
        }

        /**
         * Adds plugin prefix placeholder (%plugin-prefix%).
         */
        public ReplacementBuilder withPluginPrefix() {
            replacements.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            return this;
        }

        /**
         * Adds death message placeholder (%death_message%) for YepLib death events.
         */
        public ReplacementBuilder withDeathMessage(String deathMessage) {
            replacements.add(Tuple.of("death_message", deathMessage));
            return this;
        }

        /**
         * Adds advancement placeholders for YepLib advancement events.
         * (%title%, %description%, %advancement_type%)
         */
        public ReplacementBuilder withAdvancement(String title, String description, String advancementType) {
            replacements.add(Tuple.of("title", title));
            replacements.add(Tuple.of("description", description));
            replacements.add(Tuple.of("advancement_type", advancementType));
            return this;
        }

        /**
         * Adds a custom placeholder.
         */
        public ReplacementBuilder with(String key, String value) {
            replacements.add(Tuple.of(key, value));
            return this;
        }

        /**
         * Builds the replacement list.
         */
        public List<Tuple<String, String>> build() {
            return new ArrayList<>(replacements);
        }

        /**
         * Applies replacements to a template string.
         */
        public String apply(String template) {
            return Helper.replaceKeys(template, replacements);
        }
    }
}
