package com.beanbeanjuice.simpleproxychat.discord;

import com.beanbeanjuice.simpleproxychat.utility.ISimpleProxyChat;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;

import net.dv8tion.jda.api.entities.IncomingWebhookClient;
import net.dv8tion.jda.api.entities.WebhookClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;
 

/**
 * Lightweight Discord Webhook sender for chat mirroring.
 * Uses the standard Discord webhook JSON payload with username, avatar_url, content,
 * and optional allowed_mentions control to prevent unwanted pings.
 */
public class WebhookSender {

    private static final String DEFAULT_AVATAR_TEMPLATE = "https://crafthead.net/avatar/{uuid}";

    private final ISimpleProxyChat plugin;
    private final Config config;
    private final HttpClient httpClient;

    public WebhookSender(ISimpleProxyChat plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void send(UUID playerUUID, String playerName, String serverAlias, String originalServer, String content) {
        String url = config.get(ConfigKey.DISCORD_WEBHOOK_URL).asString();
        if (url == null || url.isBlank()) {
            plugin.log("Webhook URL is not configured (webhook.url). Skipping Discord webhook send.");
            return;
        }

        // Resolve username (from messages.yml)
        String usernameFormat = null;
        try { usernameFormat = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_USERNAME_FORMAT).asString(); } catch (Exception ignored) {}
        if (usernameFormat == null || usernameFormat.isBlank()) usernameFormat = "%player%";
        String username = usernameFormat
                .replace("%player%", playerName)
                .replace("%escaped_player%", Helper.escapeString(playerName))
                .replace("%server%", serverAlias == null ? "" : Helper.escapeString(serverAlias))
                .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer));

        // Resolve avatar URL (from messages.yml) and compute player's head URL for embed thumbnail
        String avatarTemplate = null;
        try { avatarTemplate = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_AVATAR_URL).asString(); } catch (Exception ignored) {}
        if (avatarTemplate == null || avatarTemplate.isBlank()) avatarTemplate = DEFAULT_AVATAR_TEMPLATE;
        String uuid = playerUUID.toString();
        String uuidNoDashes = uuid.replace("-", "");
        String playerHeadUrl = avatarTemplate
                .replace("{uuid}", uuid)
                .replace("{uuid-nodashes}", uuidNoDashes)
                .replace("{username}", playerName);
        String avatarUrl = playerHeadUrl;

        boolean allowMentionsTmp = false;
        try { allowMentionsTmp = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_ALLOWED_MENTIONS).asBoolean(); } catch (Exception ignored) {}
        final boolean allowMentions = allowMentionsTmp;

        String sanitizedContent = Helper.sanitize(content);

        // If mentions are globally disabled, try resolving @username -> <@id> and whitelist those IDs
        // so that only explicit usernames typed by players will ping.
        String resolvedContent = sanitizedContent;
        List<String> whitelistUserIds = new ArrayList<>();
        if (!allowMentions) {
            try {
                Bot bot = plugin.getDiscordBot();
                if (bot != null) {
                    Optional<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> optChannel = bot.getBotTextChannel();
                    if (optChannel.isPresent()) {
                        var members = optChannel.get().getMembers();
                        resolvedContent = Arrays.stream(sanitizedContent.split(" "))
                                .map(token -> {
                                    if (!token.startsWith("@")) return token;
                                    String name = token.substring(1);
                                    var match = members.stream().filter(m -> {
                                        String nick = m.getNickname();
                                        String eff = m.getEffectiveName();
                                        String user = m.getUser().getName();
                                        return (nick != null && nick.equalsIgnoreCase(name))
                                                || eff.equalsIgnoreCase(name)
                                                || user.equalsIgnoreCase(name);
                                    }).findFirst();
                                    if (match.isPresent()) {
                                        String id = match.get().getId();
                                        whitelistUserIds.add(id);
                                        return "<@" + id + ">";
                                    }
                                    return token;
                                })
                                .collect(java.util.stream.Collectors.joining(" "));
                    }
                }
            } catch (Throwable ignored) { }
        }

        // Freeze variables for lambda capture
        final String resolvedToSend = resolvedContent;
        final List<String> whitelistUserIdsFinal = whitelistUserIds;

        // Prefer JDA IncomingWebhookClient if JDA is available; fallback to raw HttpClient otherwise
        try {
            Bot bot = plugin.getDiscordBot();
            if (bot != null) {
                final String urlForLambda = url; // must be effectively final for lambda capture
                boolean sent = bot.getJDA().map(jda -> {
                    try {
                        IncomingWebhookClient client = WebhookClient.createClient(jda, urlForLambda);
                        var action = client
                                .sendMessage(resolvedToSend)
                                .setUsername(username)
                                .setAvatarUrl(avatarUrl);
                        if (!allowMentions) {
                            // Disable mention parsing
                            action.setAllowedMentions(Collections.emptyList());
                            if (!whitelistUserIdsFinal.isEmpty()) {
                                action.mentionUsers(whitelistUserIdsFinal);
                            }
                        }
                        action.queue(
                                (msg) -> {},
                                (err) -> plugin.log("JDA webhook send failed: " + err.getMessage())
                        );
                        return true;
                    } catch (Throwable t) {
                        plugin.log("JDA webhook setup failed: " + t.getMessage());
                        return false;
                    }
                }).orElse(false);

                if (sent) return; // JDA send succeeded
            }
        } catch (Throwable ignored) { }

        // Fallback: raw HTTP POST
        String json = buildPayload(username, avatarUrl, resolvedToSend, allowMentions, whitelistUserIdsFinal);
        postAsync(url, json);
    }

    /**
     * Send an embed message through the primary chat webhook, using the same embed style as bot embeds.
     * Username is formatted from messages.yml and should include %server% to use the server alias.
     */
    public void sendEmbed(UUID playerUUID, String playerName, String serverAlias, String originalServer,
                          String embedTitle, String embedDescription) {
        String url = config.get(ConfigKey.DISCORD_WEBHOOK_URL).asString();
        if (url == null || url.isBlank()) {
            plugin.log("Webhook URL is not configured (webhook.url). Skipping Discord webhook send.");
            return;
        }

        // Resolve username (from messages.yml)
        String usernameFormat = null;
        try { usernameFormat = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_USERNAME_FORMAT).asString(); } catch (Exception ignored) {}
        if (usernameFormat == null || usernameFormat.isBlank()) usernameFormat = "%player%";
        String username = usernameFormat
                .replace("%player%", playerName)
                .replace("%escaped_player%", Helper.escapeString(playerName))
                .replace("%server%", serverAlias == null ? "" : Helper.escapeString(serverAlias))
                .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer));

        // Resolve avatar URL (from messages.yml) and compute player's head URL for embed author icon
        String avatarTemplate = null;
        try { avatarTemplate = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_AVATAR_URL).asString(); } catch (Exception ignored) {}
        if (avatarTemplate == null || avatarTemplate.isBlank()) avatarTemplate = DEFAULT_AVATAR_TEMPLATE;
        String uuid = playerUUID.toString();
        String uuidNoDashes = uuid.replace("-", "");
        String playerHeadUrl = avatarTemplate
                .replace("{uuid}", uuid)
                .replace("{uuid-nodashes}", uuidNoDashes)
                .replace("{username}", playerName);
        String avatarUrl = playerHeadUrl;

        boolean allowMentionsTmp = false;
        try { allowMentionsTmp = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_ALLOWED_MENTIONS).asBoolean(); } catch (Exception ignored) {}
        final boolean allowMentions = allowMentionsTmp;

        String sanitizedDescription = Helper.sanitize(embedDescription);

        // If mentions are globally disabled, resolve @username -> <@id> and whitelist those IDs for embeds as well
        String resolvedDescription = sanitizedDescription;
        List<String> whitelistUserIds = new ArrayList<>();
        if (!allowMentions) {
            try {
                Bot bot = plugin.getDiscordBot();
                if (bot != null) {
                    Optional<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> optChannel = bot.getBotTextChannel();
                    if (optChannel.isPresent()) {
                        var members = optChannel.get().getMembers();
                        resolvedDescription = Arrays.stream(sanitizedDescription.split(" "))
                                .map(token -> {
                                    if (!token.startsWith("@")) return token;
                                    String name = token.substring(1);
                                    var match = members.stream().filter(m -> {
                                        String nick = m.getNickname();
                                        String eff = m.getEffectiveName();
                                        String user = m.getUser().getName();
                                        return (nick != null && nick.equalsIgnoreCase(name))
                                                || eff.equalsIgnoreCase(name)
                                                || user.equalsIgnoreCase(name);
                                    }).findFirst();
                                    if (match.isPresent()) {
                                        String id = match.get().getId();
                                        whitelistUserIds.add(id);
                                        return "<@" + id + ">";
                                    }
                                    return token;
                                })
                                .collect(java.util.stream.Collectors.joining(" "));
                    }
                }
            } catch (Throwable ignored) { }
        }

        // Embed style from config
        java.awt.Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();
        int colorInt = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        boolean useTimestamp = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_USE_TIMESTAMP).asBoolean();

        String json = buildEmbedPayload(username, avatarUrl,
                embedTitle == null ? "" : embedTitle.trim(), avatarUrl,
                null,
                null,
                resolvedDescription, colorInt, useTimestamp,
                allowMentions, whitelistUserIds);
        postAsync(url, json);
    }

    

    public void sendEventEmbed(UUID playerUUID, String playerName, String serverAlias, String originalServer,
                               com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType eventType,
                               String embedTitle, String embedDescription, String rawEventMessage) {
        String url = Optional.ofNullable(config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_URL).asString()).orElse("");
        if (url == null || url.isBlank()) {
            plugin.log("Events webhook URL is not configured (webhook.events-url). Skipping events webhook embed send.");
            return;
        }

        // Per-server overrides (alias + avatar) for events
        try {
            String overrideAlias = config.getEventWebhookAliasOverride(originalServer);
            if (overrideAlias != null && !overrideAlias.isBlank()) {
                serverAlias = overrideAlias;
            }
        } catch (Throwable ignored) { }

        // Resolve username (events-specific, default to %server%)
        String usernameFormat = null;
        try { usernameFormat = config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_USERNAME_FORMAT).asString(); } catch (Exception ignored) {}
        if (usernameFormat == null || usernameFormat.isBlank()) usernameFormat = "%server%";
        String username = usernameFormat
                .replace("%player%", playerName)
                .replace("%escaped_player%", Helper.escapeString(playerName))
                .replace("%server%", serverAlias == null ? "" : Helper.escapeString(serverAlias))
                .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer));

        // Resolve avatar URL (from messages.yml) and compute player's head URL for embed thumbnail
        String avatarTemplate = null;
        try { avatarTemplate = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_AVATAR_URL).asString(); } catch (Exception ignored) {}
        if (avatarTemplate == null || avatarTemplate.isBlank()) avatarTemplate = DEFAULT_AVATAR_TEMPLATE;
        String uuid = playerUUID.toString();
        String uuidNoDashes = uuid.replace("-", "");
        String playerHeadUrl = avatarTemplate
                .replace("{uuid}", uuid)
                .replace("{uuid-nodashes}", uuidNoDashes)
                .replace("{username}", playerName);
        String avatarUrl = playerHeadUrl;

        // Apply avatar override if present for this server (events only)
        try {
            String avatarOverride = config.getEventWebhookAvatarOverride(originalServer);
            if (avatarOverride != null && !avatarOverride.isBlank()) {
                avatarUrl = avatarOverride;
            }
        } catch (Throwable ignored) { }

        boolean allowMentionsTmp = false;
        try { allowMentionsTmp = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_ALLOWED_MENTIONS).asBoolean(); } catch (Exception ignored) {}
        final boolean allowMentions = allowMentionsTmp;

        String sanitizedDescription = Helper.sanitize(embedDescription);

        // If mentions are globally disabled, resolve @username -> <@id> and whitelist those IDs for embeds as well
        String resolvedDescription = sanitizedDescription;
        List<String> whitelistUserIds = new ArrayList<>();
        if (!allowMentions) {
            try {
                Bot bot = plugin.getDiscordBot();
                if (bot != null) {
                    Optional<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> optChannel = bot.getBotTextChannel();
                    if (optChannel.isPresent()) {
                        var members = optChannel.get().getMembers();
                        resolvedDescription = Arrays.stream(sanitizedDescription.split(" "))
                                .map(token -> {
                                    if (!token.startsWith("@")) return token;
                                    String name = token.substring(1);
                                    var match = members.stream().filter(m -> {
                                        String nick = m.getNickname();
                                        String eff = m.getEffectiveName();
                                        String user = m.getUser().getName();
                                        return (nick != null && nick.equalsIgnoreCase(name))
                                                || eff.equalsIgnoreCase(name)
                                                || user.equalsIgnoreCase(name);
                                    }).findFirst();
                                    if (match.isPresent()) {
                                        String id = match.get().getId();
                                        whitelistUserIds.add(id);
                                        return "<@" + id + ">";
                                    }
                                    return token;
                                })
                                .collect(java.util.stream.Collectors.joining(" "));
                    }
                }
            } catch (Throwable ignored) { }
        }

        // Embed style from config
        java.awt.Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();
        int colorInt = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        boolean useTimestamp = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_USE_TIMESTAMP).asBoolean();
        try {
            if (eventType == com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType.DEATH) {
                useTimestamp = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_TIMESTAMP).asBoolean();
            } else if (eventType == com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType.ADVANCEMENT) {
                useTimestamp = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_TIMESTAMP).asBoolean();
            }
        } catch (Throwable ignored) { }

        String authorForEmbed = null;
        String authorIconForEmbed = null;
        String titleForEmbed = embedTitle == null ? "" : embedTitle.trim();
        String finalDescription = resolvedDescription;
        if (eventType == com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType.DEATH) {
            boolean useAuthor = true;
            boolean useAuthorIcon = true;
            try { useAuthor = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_AUTHOR).asBoolean(); } catch (Throwable ignored) {}
            try { useAuthorIcon = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_AUTHOR_ICON).asBoolean(); } catch (Throwable ignored) {}

            if (useAuthor) {
                String authorTextTpl = null;
                try { authorTextTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_AUTHOR_TEXT).asString(); } catch (Throwable ignored) {}
                if (authorTextTpl != null && !authorTextTpl.isBlank()) {
                    String at = authorTextTpl
                            .replace("%player%", playerName)
                            .replace("%server%", serverAlias == null ? "" : Helper.escapeString(serverAlias))
                            .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer))
                            .replace("%death_message%", (rawEventMessage == null || rawEventMessage.isBlank()) ?
                                    (embedTitle == null ? "" : embedTitle) : rawEventMessage);
                    authorForEmbed = Helper.sanitize(at).trim();
                } else {
                    // Ensure author block is present so icon can render, without falling back to title
                    authorForEmbed = "\u200B";
                }
                if (useAuthorIcon) {
                    String iconTpl = null;
                    try { iconTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_AUTHOR_ICON_URL).asString(); } catch (Throwable ignored) {}
                    if (iconTpl != null && !iconTpl.isBlank()) {
                        authorIconForEmbed = iconTpl
                                .replace("{uuid}", uuid)
                                .replace("{uuid-nodashes}", uuidNoDashes)
                                .replace("{username}", playerName);
                    } else {
                        authorIconForEmbed = playerHeadUrl;
                    }
                }
            }
            // Do not override title/description; respect messages.yml values
        } else if (eventType == com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType.ADVANCEMENT) {
            boolean useAuthor = false; // default off to preserve previous style
            boolean useAuthorIcon = true;
            try { useAuthor = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_AUTHOR).asBoolean(); } catch (Throwable ignored) {}
            try { useAuthorIcon = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_AUTHOR_ICON).asBoolean(); } catch (Throwable ignored) {}

            if (useAuthor) {
                String authorTextTpl = null;
                try { authorTextTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_AUTHOR_TEXT).asString(); } catch (Throwable ignored) {}
                if (authorTextTpl != null && !authorTextTpl.isBlank()) {
                    String at = authorTextTpl
                            .replace("%player%", playerName)
                            .replace("%server%", serverAlias == null ? "" : Helper.escapeString(serverAlias))
                            .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer))
                            .replace("%title%", embedTitle == null ? "" : embedTitle)
                            .replace("%description%", embedDescription == null ? "" : embedDescription);
                    authorForEmbed = Helper.sanitize(at).trim();
                } else {
                    authorForEmbed = "\u200B";
                }
                if (useAuthorIcon) {
                    String iconTpl = null;
                    try { iconTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_AUTHOR_ICON_URL).asString(); } catch (Throwable ignored) {}
                    if (iconTpl != null && !iconTpl.isBlank()) {
                        authorIconForEmbed = iconTpl
                                .replace("{uuid}", uuid)
                                .replace("{uuid-nodashes}", uuidNoDashes)
                                .replace("{username}", playerName);
                    } else {
                        authorIconForEmbed = playerHeadUrl;
                    }
                }
            }
        }

        String json = buildEmbedPayload(username, avatarUrl,
                authorForEmbed, authorIconForEmbed,
                null,
                titleForEmbed,
                finalDescription, colorInt, useTimestamp,
                allowMentions, whitelistUserIds);
        postAsync(url, json);
    }

    public void sendEvent(UUID playerUUID, String playerName, String serverAlias, String originalServer, String content) {
        // Prefer dedicated events webhook if configured
        String url = Optional.ofNullable(config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_URL).asString()).orElse("");
        // No fallback to chat/legacy webhook here. If no events URL is present, caller should handle fallback (e.g., bot embed).
        if (url == null || url.isBlank()) {
            plugin.log("Events webhook URL is not configured (webhook.events-url). Skipping events webhook send.");
            return;
        }

        // Per-server overrides (alias + avatar) for events
        try {
            String overrideAlias = config.getEventWebhookAliasOverride(originalServer);
            if (overrideAlias != null && !overrideAlias.isBlank()) {
                serverAlias = overrideAlias;
            }
        } catch (Throwable ignored) { }

        // Resolve username (events-specific, default to %server%)
        String usernameFormat = null;
        try { usernameFormat = config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_USERNAME_FORMAT).asString(); } catch (Exception ignored) {}
        if (usernameFormat == null || usernameFormat.isBlank()) usernameFormat = "%server%";
        String username = usernameFormat
                .replace("%player%", playerName)
                .replace("%escaped_player%", Helper.escapeString(playerName))
                .replace("%server%", serverAlias == null ? "" : Helper.escapeString(serverAlias))
                .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer));

        // Resolve avatar URL
        String avatarTemplate = null;
        try { avatarTemplate = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_AVATAR_URL).asString(); } catch (Exception ignored) {}
        if (avatarTemplate == null || avatarTemplate.isBlank()) avatarTemplate = DEFAULT_AVATAR_TEMPLATE;
        String uuid = playerUUID.toString();
        String uuidNoDashes = uuid.replace("-", "");
        String avatarUrl = avatarTemplate
                .replace("{uuid}", uuid)
                .replace("{uuid-nodashes}", uuidNoDashes)
                .replace("{username}", playerName);

        // Apply avatar override if present for this server (events only)
        try {
            String avatarOverride = config.getEventWebhookAvatarOverride(originalServer);
            if (avatarOverride != null && !avatarOverride.isBlank()) {
                avatarUrl = avatarOverride;
            }
        } catch (Throwable ignored) { }

        boolean allowMentionsTmp = false;
        try { allowMentionsTmp = config.get(ConfigKey.MINECRAFT_DISCORD_WEBHOOK_ALLOWED_MENTIONS).asBoolean(); } catch (Exception ignored) {}
        final boolean allowMentions = allowMentionsTmp;

        String sanitizedContent = Helper.sanitize(content);

        // Resolve mentions to IDs if mentions are disabled
        String resolvedContent = sanitizedContent;
        List<String> whitelistUserIds = new ArrayList<>();
        if (!allowMentions) {
            try {
                Bot bot = plugin.getDiscordBot();
                if (bot != null) {
                    Optional<net.dv8tion.jda.api.entities.channel.concrete.TextChannel> optChannel = bot.getBotTextChannel();
                    if (optChannel.isPresent()) {
                        var members = optChannel.get().getMembers();
                        resolvedContent = Arrays.stream(sanitizedContent.split(" "))
                                .map(token -> {
                                    if (!token.startsWith("@")) return token;
                                    String name = token.substring(1);
                                    var match = members.stream().filter(m -> {
                                        String nick = m.getNickname();
                                        String eff = m.getEffectiveName();
                                        String user = m.getUser().getName();
                                        return (nick != null && nick.equalsIgnoreCase(name))
                                                || eff.equalsIgnoreCase(name)
                                                || user.equalsIgnoreCase(name);
                                    }).findFirst();
                                    if (match.isPresent()) {
                                        String id = match.get().getId();
                                        whitelistUserIds.add(id);
                                        return "<@" + id + ">";
                                    }
                                    return token;
                                })
                                .collect(java.util.stream.Collectors.joining(" "));
                    }
                }
            } catch (Throwable ignored) { }
        }

        final String resolvedToSend = resolvedContent;
        final List<String> whitelistUserIdsFinal = whitelistUserIds;

        final String usernameFinal = username;
        final String avatarUrlFinal = avatarUrl;

        try {
            Bot bot = plugin.getDiscordBot();
            if (bot != null) {
                final String urlForLambda = url; // must be effectively final for lambda capture
                boolean sent = bot.getJDA().map(jda -> {
                    try {
                        IncomingWebhookClient client = WebhookClient.createClient(jda, urlForLambda);
                        var action = client
                                .sendMessage(resolvedToSend)
                                .setUsername(usernameFinal)
                                .setAvatarUrl(avatarUrlFinal);
                        if (!allowMentions) {
                            action.setAllowedMentions(Collections.emptyList());
                            if (!whitelistUserIdsFinal.isEmpty()) {
                                action.mentionUsers(whitelistUserIdsFinal);
                            }
                        }
                        action.queue(
                                (msg) -> {},
                                (err) -> plugin.log("JDA webhook send failed: " + err.getMessage())
                        );
                        return true;
                    } catch (Throwable t) {
                        plugin.log("JDA webhook setup failed: " + t.getMessage());
                        return false;
                    }
                }).orElse(false);

                if (sent) return;
            }
        } catch (Throwable ignored) { }

        String json = buildPayload(username, avatarUrl, resolvedToSend, allowMentions, whitelistUserIdsFinal);
        postAsync(url, json);
    }

    private void postAsync(String url, String jsonPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        int code = response.statusCode();
                        if (code >= 200 && code < 300) return;
                        plugin.log("Discord webhook responded with status code: " + code);
                    })
                    .exceptionally(ex -> {
                        plugin.log("Error sending Discord webhook: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            plugin.log("Failed to submit Discord webhook request: " + e.getMessage());
        }
    }

    private String buildPayload(String username, String avatarUrl, String content, boolean allowMentions, Collection<String> whitelistUserIds) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"username\":\"").append(jsonEscape(username)).append('\"');
        sb.append(',');
        sb.append("\"avatar_url\":\"").append(jsonEscape(avatarUrl)).append('\"');
        sb.append(',');
        sb.append("\"content\":\"").append(jsonEscape(content)).append('\"');
        if (!allowMentions) {
            sb.append(',');
            if (whitelistUserIds != null && !whitelistUserIds.isEmpty()) {
                sb.append("\"allowed_mentions\":{\"parse\":[],\"users\":[");
                boolean first = true;
                for (String id : whitelistUserIds) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('\"').append(jsonEscape(id)).append('\"');
                }
                sb.append(']');
                sb.append('}');
            } else {
                sb.append("\"allowed_mentions\":{\"parse\":[]}");
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private String buildEmbedPayload(String username, String avatarUrl,
                                     String authorName, String authorIconUrl,
                                     String thumbnailUrl,
                                     String title,
                                     String description, int colorInt, boolean useTimestamp,
                                     boolean allowMentions, Collection<String> whitelistUserIds) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"username\":\"").append(jsonEscape(username)).append('\"');
        sb.append(',');
        sb.append("\"avatar_url\":\"").append(jsonEscape(avatarUrl)).append('\"');
        sb.append(',');
        sb.append("\"embeds\":[{");
        boolean needComma = false;
        if (title != null && !title.isEmpty()) {
            sb.append("\"title\":\"").append(jsonEscape(title)).append('\"');
            needComma = true;
        }
        if (description != null && !description.isEmpty()) {
            if (needComma) sb.append(',');
            sb.append("\"description\":\"").append(jsonEscape(description)).append('\"');
            needComma = true;
        }
        if (needComma) sb.append(',');
        sb.append("\"color\":").append(colorInt);
        if (authorName != null && !authorName.isEmpty()) {
            sb.append(',');
            sb.append("\"author\":{");
            sb.append("\"name\":\"").append(jsonEscape(authorName)).append('\"');
            if (authorIconUrl != null && !authorIconUrl.isEmpty()) {
                sb.append(',');
                sb.append("\"icon_url\":\"").append(jsonEscape(authorIconUrl)).append('\"');
            }
            sb.append('}');
        }
        if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
            sb.append(',');
            sb.append("\"thumbnail\":{\"url\":\"").append(jsonEscape(thumbnailUrl)).append('\"').append('}');
        }
        if (useTimestamp) {
            sb.append(',');
            sb.append("\"timestamp\":\"").append(java.time.Instant.now().toString()).append('\"');
        }
        sb.append("}]");
        if (!allowMentions) {
            sb.append(',');
            if (whitelistUserIds != null && !whitelistUserIds.isEmpty()) {
                sb.append("\"allowed_mentions\":{\"parse\":[],\"users\":[");
                boolean first = true;
                for (String id : whitelistUserIds) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('\"').append(jsonEscape(id)).append('\"');
                }
                sb.append(']');
                sb.append('}');
            } else {
                sb.append("\"allowed_mentions\":{\"parse\":[]}");
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        String out = s.replace("\\", "\\\\").replace("\"", "\\\"");
        // Escape control characters for valid JSON strings
        out = out.replace("\r", "");
        out = out.replace("\n", "\\n");
        out = out.replace("\t", "\\t");
        return out;
    }
}
