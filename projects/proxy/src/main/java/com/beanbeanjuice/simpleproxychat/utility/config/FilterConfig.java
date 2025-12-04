package com.beanbeanjuice.simpleproxychat.utility.config;

import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import lombok.Getter;

import java.util.*;

/**
 * Handles all filter-related configuration from filter.yml.
 * Encapsulates filter settings, word replacements, and regex rules.
 */
public class FilterConfig {
    
    @Getter private boolean enabled = false;
    @Getter private boolean caseInsensitive = true;
    @Getter private boolean wholeWord = true;
    @Getter private String defaultReplacement = "[Redacted]";
    @Getter private Map<String, String> replacements = new HashMap<>();
    @Getter private List<String> globalWords = new ArrayList<>();
    @Getter private List<FilterRegexRule> regexRules = new ArrayList<>();
    
    /**
     * Represents a regex-based filter rule with separate replacements for Minecraft and Discord.
     */
    public static class FilterRegexRule {
        public final String id;
        public final String pattern;
        public final String replacementMinecraft;
        public final String replacementDiscord;
        public final String flags; // e.g., "i", "m", "s"
        
        public FilterRegexRule(String id, String pattern, String replacementMinecraft, String replacementDiscord, String flags) {
            this.id = id;
            this.pattern = pattern;
            this.replacementMinecraft = replacementMinecraft;
            this.replacementDiscord = replacementDiscord;
            this.flags = flags;
        }
        
        @Override
        public String toString() {
            return "FilterRegexRule{" +
                    "id='" + id + '\'' +
                    ", pattern='" + pattern + '\'' +
                    ", flags='" + flags + '\'' +
                    '}';
        }
    }
    
    /**
     * Loads filter configuration from the provided YAML document.
     * @param yamlFilter The YAML document containing filter configuration
     */
    public void load(YamlDocument yamlFilter) {
        if (yamlFilter == null) {
            resetToDefaults();
            return;
        }
        
        // Load basic toggles
        this.enabled = yamlFilter.getBoolean("filter.enabled", false);
        this.caseInsensitive = yamlFilter.getBoolean("filter.case-insensitive", true);
        this.wholeWord = yamlFilter.getBoolean("filter.whole-word", true);
        this.defaultReplacement = Helper.translateLegacyCodes(
                yamlFilter.getString("filter.default", "[Redacted]")
        );
        
        // Load replacements map
        this.replacements = loadReplacements(yamlFilter);
        
        // Load global words list
        this.globalWords = loadGlobalWords(yamlFilter);
        
        // Load regex rules
        this.regexRules = loadRegexRules(yamlFilter);
    }
    
    /**
     * Loads the word-to-replacement mapping from filter.replacements.
     */
    private Map<String, String> loadReplacements(YamlDocument yamlFilter) {
        Map<String, String> map = new HashMap<>();
        Section section = yamlFilter.getSection("filter.replacements");
        
        if (section != null) {
            section.getKeys().forEach(key -> {
                String keyStr = String.valueOf(key);
                String value = section.getString(keyStr);
                map.put(keyStr, Helper.translateLegacyCodes(value != null ? value : ""));
            });
        }
        
        return Collections.unmodifiableMap(map);
    }
    
    /**
     * Loads the global words list from filter.global-words.
     */
    private List<String> loadGlobalWords(YamlDocument yamlFilter) {
        List<String> globals = yamlFilter.getStringList("filter.global-words");
        if (globals == null) {
            return Collections.emptyList();
        }
        
        return globals.stream()
                .map(Helper::translateLegacyCodes)
                .toList();
    }
    
    /**
     * Loads regex filter rules from filter.regex.rules.
     */
    private List<FilterRegexRule> loadRegexRules(YamlDocument yamlFilter) {
        List<FilterRegexRule> rules = new ArrayList<>();
        Section rulesSection = yamlFilter.getSection("filter.regex.rules");
        
        if (rulesSection == null) {
            return Collections.emptyList();
        }
        
        for (Object key : rulesSection.getKeys()) {
            String id = String.valueOf(key);
            Section ruleSection = rulesSection.getSection(id);
            
            if (ruleSection == null) continue;
            
            String pattern = ruleSection.getString("pattern");
            String replacementMc = ruleSection.getString("replacement-minecraft");
            String replacementDc = ruleSection.getString("replacement-discord");
            String flags = ruleSection.getString("flags");
            
            if (pattern != null && !pattern.isEmpty()) {
                rules.add(new FilterRegexRule(id, pattern, replacementMc, replacementDc, flags));
            }
        }
        
        return Collections.unmodifiableList(rules);
    }
    
    /**
     * Resets all filter settings to defaults.
     */
    private void resetToDefaults() {
        this.enabled = false;
        this.caseInsensitive = true;
        this.wholeWord = true;
        this.defaultReplacement = "[Redacted]";
        this.replacements = Collections.emptyMap();
        this.globalWords = Collections.emptyList();
        this.regexRules = Collections.emptyList();
    }
}

