package com.beanbeanjuice.simpleproxychat.chat;

import com.beanbeanjuice.simpleproxychat.discord.Bot;
import com.beanbeanjuice.simpleproxychat.discord.WebhookSender;
import com.beanbeanjuice.simpleproxychat.discord.DiscordChatHandler;
import com.beanbeanjuice.simpleproxychat.socket.ChatMessageData;
import com.beanbeanjuice.simpleproxychat.utility.ISimpleProxyChat;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.config.FilterConfig;
import com.beanbeanjuice.simpleproxychat.utility.config.Permission;
import com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType;
import com.beanbeanjuice.simpleproxychat.utility.epoch.EpochHelper;
import com.beanbeanjuice.simpleproxychat.utility.helper.LastMessagesHelper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.query.QueryOptions;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatHandler {

    private static final String MINECRAFT_PLAYER_HEAD_URL = "https://crafthead.net/avatar/{PLAYER_UUID}";

    private final ISimpleProxyChat plugin;
    private final Config config;
    private final Bot discordBot;
    private final WebhookSender webhookSender;
    private final LastMessagesHelper lastMessagesHelper;
    private final MessageFormatter messageFormatter;

    // Cache for compiled regex patterns to avoid recompilation on every message
    private static class CompiledRegexRule {
        final Pattern pattern;
        final String replacementMinecraft;
        final String replacementDiscord;

        CompiledRegexRule(Pattern pattern, String replacementMinecraft, String replacementDiscord) {
            this.pattern = pattern;
            this.replacementMinecraft = replacementMinecraft;
            this.replacementDiscord = replacementDiscord;
        }
    }

    private volatile List<CompiledRegexRule> compiledRegexCache = new ArrayList<>();

    public ChatHandler(ISimpleProxyChat plugin) {
        this.plugin = plugin;
        this.config = plugin.getSPCConfig();
        this.discordBot = plugin.getDiscordBot();
        this.webhookSender = new WebhookSender(plugin, this.config);
        this.lastMessagesHelper = new LastMessagesHelper(plugin.getSPCConfig());
        this.messageFormatter = new MessageFormatter(this.config);

        // Pre-compile regex patterns for performance
        rebuildRegexCache();

        plugin.getDiscordBot().addRunnableToQueue(() -> plugin.getDiscordBot().getJDA()
                .ifPresent((jda) -> jda.addEventListener(new DiscordChatHandler(config, this::sendFromDiscord))));
    }

    /**
     * Rebuilds the regex pattern cache from config. Call this after config reload.
     */
    public void rebuildRegexCache() {
        List<FilterConfig.FilterRegexRule> rules = config.getFilterConfig().getRegexRules();
        List<CompiledRegexRule> newCache = new ArrayList<>();

        for (FilterConfig.FilterRegexRule r : rules) {
            if (r == null || r.pattern == null || r.pattern.isEmpty())
                continue;

            int flags = 0;
            if (r.flags != null) {
                if (r.flags.contains("i"))
                    flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                if (r.flags.contains("m"))
                    flags |= Pattern.MULTILINE;
                if (r.flags.contains("s"))
                    flags |= Pattern.DOTALL;
            }

            try {
                Pattern compiled = Pattern.compile(r.pattern, flags);
                String replMc = r.replacementMinecraft != null ? r.replacementMinecraft : "";
                String replDc = r.replacementDiscord != null ? r.replacementDiscord : "";
                newCache.add(new CompiledRegexRule(compiled, replMc, replDc));
            } catch (Exception e) {
                // Log and skip invalid patterns
                plugin.log("Invalid regex pattern in filter config: " + r.pattern + " - " + e.getMessage());
            }
        }

        this.compiledRegexCache = newCache;
    }

    private Optional<String> getValidMessage(String message) {
        String messagePrefix = config.get(ConfigKey.PROXY_MESSAGE_PREFIX).asString();
        String messagePrefixBlacklist = config.get(ConfigKey.PROXY_MESSAGE_PREFIX_BLACKLIST).asString();

        if (!messagePrefixBlacklist.isEmpty() && message.startsWith(messagePrefixBlacklist))
            return Optional.empty();

        if (messagePrefix.isEmpty())
            return Optional.of(message);
        if (!message.startsWith(messagePrefix))
            return Optional.empty();

        message = message.substring(messagePrefix.length());
        if (message.isEmpty())
            return Optional.empty();
        return Optional.of(message);
    }

    public void chat(ChatMessageData chatMessageData, String minecraftMessage, String discordMessage,
            String discordEmbedTitle, String discordEmbedMessage) {
        // Log to Console
        if (config.get(ConfigKey.CONSOLE_CHAT).asBoolean())
            plugin.log(minecraftMessage);

        // Log to Discord
        if (config.get(ConfigKey.MINECRAFT_DISCORD_ENABLED).asBoolean()) {
            // Events (advancement/death) can be routed to a separate webhook regardless of
            // global mode
            boolean sentToEventsWebhook = false;
            if (chatMessageData.getType() == MessageType.ADVANCEMENT &&
                    config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_WEBHOOK_SEND).asBoolean()) {
                String originalServer = chatMessageData.getServername();
                String aliasedServer = Helper.convertAlias(config, originalServer);
                // Resolve events webhook (single URL)
                String eventsUrl = java.util.Optional
                        .ofNullable(config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_URL).asString()).orElse("");

                if (eventsUrl != null && !eventsUrl.isBlank()) {
                    webhookSender.sendEventEmbed(
                            chatMessageData.getPlayerUUID(),
                            chatMessageData.getPlayerName(),
                            aliasedServer,
                            originalServer,
                            MessageType.ADVANCEMENT,
                            discordEmbedTitle,
                            discordEmbedMessage,
                            null);
                } else {
                    // Fallback to bot embed (no webhooks)
                    java.awt.Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();
                    EmbedBuilder embedBuilder = new EmbedBuilder().setColor(color);

                    // Respect messages.yml advancement embed toggles
                    boolean useAuthor = false; // default off for advancement
                    boolean useAuthorIcon = true;
                    try {
                        useAuthor = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_AUTHOR).asBoolean();
                    } catch (Throwable ignored) {
                    }
                    try {
                        useAuthorIcon = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_AUTHOR_ICON).asBoolean();
                    } catch (Throwable ignored) {
                    }

                    // Title/Description from passed-in values (already formatted for event)
                    String _title = java.util.Optional.ofNullable(discordEmbedTitle).orElse("").trim();
                    String _desc = java.util.Optional.ofNullable(discordEmbedMessage).orElse("").trim();
                    if (!_title.isEmpty())
                        embedBuilder.setTitle(_title);
                    if (!_desc.isEmpty())
                        embedBuilder.setDescription(_desc);

                    if (useAuthor) {
                        String authorTextTpl = null;
                        try {
                            authorTextTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_AUTHOR_TEXT).asString();
                        } catch (Throwable ignored) {
                        }
                        String authorText;
                        if (authorTextTpl != null && !authorTextTpl.isBlank()) {
                            authorText = authorTextTpl
                                    .replace("%player%", chatMessageData.getPlayerName())
                                    .replace("%server%",
                                            aliasedServer == null ? "" : Helper.escapeString(aliasedServer))
                                    .replace("%original_server%",
                                            originalServer == null ? "" : Helper.escapeString(originalServer))
                                    .replace("%title%", _title)
                                    .replace("%description%", _desc);
                            authorText = Helper.sanitize(authorText).trim();
                        } else {
                            authorText = "\u200B"; // ensure author row exists so icon can render
                        }
                        String authorIcon = null;
                        if (useAuthorIcon) {
                            String iconTpl = null;
                            try {
                                iconTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_AUTHOR_ICON_URL)
                                        .asString();
                            } catch (Throwable ignored) {
                            }
                            if (iconTpl != null && !iconTpl.isBlank()) {
                                String uuid = chatMessageData.getPlayerUUID().toString();
                                String uuidNoDashes = uuid.replace("-", "");
                                authorIcon = iconTpl
                                        .replace("{uuid}", uuid)
                                        .replace("{uuid-nodashes}", uuidNoDashes)
                                        .replace("{username}", chatMessageData.getPlayerName());
                            } else {
                                authorIcon = getPlayerHeadURL(chatMessageData.getPlayerUUID());
                            }
                        }
                        embedBuilder.setAuthor(authorText, null, authorIcon);
                    }
                    if (config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_USE_TIMESTAMP).asBoolean())
                        embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
                    // Route to override channel if configured
                    String overrideId = resolveOverrideChannelId(MessageType.ADVANCEMENT);
                    discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
                }
                sentToEventsWebhook = true;
            } else if (chatMessageData.getType() == MessageType.DEATH &&
                    config.get(ConfigKey.DISCORD_YEP_DEATH_WEBHOOK_SEND).asBoolean()) {
                String originalServer = chatMessageData.getServername();
                String aliasedServer = Helper.convertAlias(config, originalServer);
                // Resolve events webhook (single URL)
                String eventsUrl = java.util.Optional
                        .ofNullable(config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_URL).asString()).orElse("");

                if (eventsUrl != null && !eventsUrl.isBlank()) {
                    webhookSender.sendEventEmbed(
                            chatMessageData.getPlayerUUID(),
                            chatMessageData.getPlayerName(),
                            aliasedServer,
                            originalServer,
                            MessageType.DEATH,
                            discordEmbedTitle,
                            discordEmbedMessage,
                            chatMessageData.getMessage());
                } else {
                    // Fallback to bot embed (no webhooks)
                    java.awt.Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();
                    EmbedBuilder embedBuilder = new EmbedBuilder().setColor(color);

                    // Respect messages.yml death embed toggles
                    boolean useAuthor = true;
                    boolean useAuthorIcon = true;
                    try {
                        useAuthor = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_AUTHOR).asBoolean();
                    } catch (Throwable ignored) {
                    }
                    try {
                        useAuthorIcon = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_AUTHOR_ICON).asBoolean();
                    } catch (Throwable ignored) {
                    }

                    // Title/Description from passed-in values (already formatted for event)
                    String _title = java.util.Optional.ofNullable(discordEmbedTitle).orElse("").trim();
                    String _desc = java.util.Optional.ofNullable(discordEmbedMessage).orElse("").trim();
                    if (!_title.isEmpty())
                        embedBuilder.setTitle(_title);
                    if (!_desc.isEmpty())
                        embedBuilder.setDescription(_desc);

                    if (useAuthor) {
                        String authorTextTpl = null;
                        try {
                            authorTextTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_AUTHOR_TEXT).asString();
                        } catch (Throwable ignored) {
                        }
                        String authorText;
                        if (authorTextTpl != null && !authorTextTpl.isBlank()) {
                            String raw = chatMessageData.getMessage();
                            authorText = authorTextTpl
                                    .replace("%player%", chatMessageData.getPlayerName())
                                    .replace("%server%",
                                            aliasedServer == null ? "" : Helper.escapeString(aliasedServer))
                                    .replace("%original_server%",
                                            originalServer == null ? "" : Helper.escapeString(originalServer))
                                    .replace("%death_message%", (raw == null || raw.isBlank()) ? _title : raw);
                            authorText = Helper.sanitize(authorText).trim();
                        } else {
                            authorText = "\u200B"; // ensure author row exists so icon can render
                        }
                        String authorIcon = null;
                        if (useAuthorIcon) {
                            String iconTpl = null;
                            try {
                                iconTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_AUTHOR_ICON_URL).asString();
                            } catch (Throwable ignored) {
                            }
                            if (iconTpl != null && !iconTpl.isBlank()) {
                                String uuid = chatMessageData.getPlayerUUID().toString();
                                String uuidNoDashes = uuid.replace("-", "");
                                authorIcon = iconTpl
                                        .replace("{uuid}", uuid)
                                        .replace("{uuid-nodashes}", uuidNoDashes)
                                        .replace("{username}", chatMessageData.getPlayerName());
                            } else {
                                authorIcon = getPlayerHeadURL(chatMessageData.getPlayerUUID());
                            }
                        }
                        embedBuilder.setAuthor(authorText, null, authorIcon);
                    }
                    if (config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_USE_TIMESTAMP).asBoolean())
                        embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
                    // Route to override channel if configured
                    String overrideId = resolveOverrideChannelId(MessageType.DEATH);
                    discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
                }
                sentToEventsWebhook = true;
            }

            if (!sentToEventsWebhook) {
                String mode = Optional.ofNullable(config.get(ConfigKey.MINECRAFT_DISCORD_MODE).asString())
                        .map(s -> s.toLowerCase(Locale.ROOT)).orElse("plain");
                if ("webhook".equals(mode)) {
                    String originalServer = chatMessageData.getServername();
                    String aliasedServer = Helper.convertAlias(config, originalServer);
                    // Plain webhook message: username = player, avatar = player, content =
                    // discordMessage
                    webhookSender.send(
                            chatMessageData.getPlayerUUID(),
                            chatMessageData.getPlayerName(),
                            aliasedServer,
                            originalServer,
                            discordMessage);
                } else if ("embed".equals(mode)) {
                    Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();

                    EmbedBuilder embedBuilder = new EmbedBuilder()
                            .setDescription(discordEmbedMessage)
                            .setColor(color);

                    String _title = Optional.ofNullable(discordEmbedTitle).orElse("").trim();
                    if (!_title.isEmpty()) {
                        embedBuilder.setAuthor(_title, null, getPlayerHeadURL(chatMessageData.getPlayerUUID()));
                    }

                    if (config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_USE_TIMESTAMP).asBoolean())
                        embedBuilder.setTimestamp(EpochHelper.getEpochInstant());

                    // Route to override channel if configured for chat
                    String overrideId = resolveOverrideChannelId(MessageType.CHAT);
                    discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
                } else {
                    // plain
                    String overrideId = resolveOverrideChannelId(MessageType.CHAT);
                    discordBot.sendMessageToChannelId(overrideId, discordMessage);
                }
            }
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_CHAT_ENABLED).asBoolean()) {
            boolean allowProxy = true;
            MessageType type = chatMessageData.getType();
            if (type == MessageType.ADVANCEMENT) {
                allowProxy = config.get(ConfigKey.MINECRAFT_YEP_ADVANCEMENT_PROXY_SEND).asBoolean();
            } else if (type == MessageType.DEATH) {
                allowProxy = config.get(ConfigKey.MINECRAFT_YEP_DEATH_PROXY_SEND).asBoolean();
            }

            if (allowProxy) {
                chatMessageData.chatSendToAllOtherPlayers(minecraftMessage);
            }
            lastMessagesHelper.addMessage(minecraftMessage);
        }

    }

    public void runProxyChatMessage(ChatMessageData chatMessageData) {
        if (Helper.serverHasChatLocked(plugin, chatMessageData.getServername()))
            return;

        String playerMessage = chatMessageData.getMessage();
        String serverName = chatMessageData.getServername();
        String playerName = chatMessageData.getPlayerName();
        UUID playerUUID = chatMessageData.getPlayerUUID();

        Optional<String> optionalPlayerMessage = getValidMessage(playerMessage);
        if (optionalPlayerMessage.isEmpty())
            return;
        playerMessage = optionalPlayerMessage.get();

        // Apply word filter before any linkification or formatting
        playerMessage = applyFilter(playerMessage);

        // Apply regex rules; linkifier removed in favor of regex-based replacements
        String mcMessagePart = applyRegexRules(playerMessage, true);
        String dcMessagePart = applyRegexRules(playerMessage, false);

        String minecraftConfigString = config.get(ConfigKey.MINECRAFT_CHAT_MESSAGE).asString();
        String discordConfigString = config.get(ConfigKey.MINECRAFT_DISCORD_MESSAGE).asString();

        String aliasedServerName = Helper.convertAlias(config, serverName);
        String timeString = messageFormatter.getTimeString();

        // Build replacements using MessageFormatter
        String minecraftMessage = messageFormatter.builder()
                .withMessage(mcMessagePart)
                .withPlayer(playerName)
                .withServer(aliasedServerName, serverName)
                .withTime(timeString)
                .withPluginPrefix()
                .apply(minecraftConfigString);

        String discordMessage = messageFormatter.builder()
                .withMessage(dcMessagePart)
                .withPlayer(playerName)
                .withServer(aliasedServerName, serverName)
                .withTime(timeString)
                .withPluginPrefix()
                .apply(discordConfigString);

        String discordEmbedTitle = messageFormatter.builder()
                .withMessage(dcMessagePart)
                .withPlayer(playerName)
                .withServer(aliasedServerName, serverName)
                .withTime(timeString)
                .withPluginPrefix()
                .apply(config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_TITLE).asString());

        String discordEmbedMessage = messageFormatter.builder()
                .withMessage(dcMessagePart)
                .withPlayer(playerName)
                .withServer(aliasedServerName, serverName)
                .withTime(timeString)
                .withPluginPrefix()
                .apply(config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_MESSAGE).asString());

        minecraftMessage = replacePrefixSuffix(minecraftMessage, playerUUID, aliasedServerName, serverName);
        discordMessage = replacePrefixSuffix(discordMessage, playerUUID, aliasedServerName, serverName);
        discordEmbedTitle = replacePrefixSuffix(discordEmbedTitle, chatMessageData.getPlayerUUID(), aliasedServerName,
                chatMessageData.getServername());

        if (config.get(ConfigKey.USE_HELPER).asBoolean()) {
            chatMessageData.setMinecraftMessage(minecraftMessage);
            chatMessageData.setDiscordMessage(discordMessage);
            chatMessageData.setDiscordEmbedTitle(discordEmbedTitle);
            chatMessageData.setDiscordEmbedMessage(discordEmbedMessage);
            chatMessageData.startPluginMessage();
            return;
        }

        chat(chatMessageData, minecraftMessage, discordMessage, discordEmbedTitle, discordEmbedMessage);
    }

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\bhttps?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    // Applies filtering to non-URL parts of the text
    private String applyFilter(String text) {
        if (!config.isFilterEnabled() || text == null || text.isEmpty())
            return text;
        Matcher m = URL_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String before = text.substring(last, m.start());
            out.append(applyFilterPlain(before));
            out.append(m.group()); // keep URL intact
            last = m.end();
        }
        out.append(applyFilterPlain(text.substring(last)));
        return out.toString();
    }

    private String applyFilterPlain(String input) {
        if (input.isEmpty())
            return input;
        String result = input;

        // Build combined replacement map: specific replacements + global words ->
        // default
        Map<String, String> combined = new LinkedHashMap<>();
        Map<String, String> specific = Optional.ofNullable(config.getFilterReplacements())
                .orElseGet(Collections::emptyMap);
        combined.putAll(specific);
        List<String> globals = Optional.ofNullable(config.getFilterGlobalWords()).orElseGet(Collections::emptyList);
        for (String gw : globals) {
            if (!combined.containsKey(gw))
                combined.put(gw, config.getFilterDefaultReplacement());
        }

        if (combined.isEmpty())
            return result;

        int flags = 0;
        if (config.isFilterCaseInsensitive())
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

        for (Map.Entry<String, String> e : combined.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty())
                continue;
            String replacement = e.getValue() == null ? "" : e.getValue();
            String core = Pattern.quote(key);
            String pattern = config.isFilterWholeWord() ? "\\b" + core + "\\b" : core;
            result = Pattern.compile(pattern, flags).matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    // Maximum time allowed for a single regex replacement (ms)
    private static final long REGEX_TIMEOUT_MS = 100;

    private String applyRegexRules(String text, boolean forMinecraft) {
        if (text == null || text.isEmpty() || compiledRegexCache.isEmpty())
            return text;

        String result = text;
        // Use cached compiled patterns for better performance
        for (CompiledRegexRule rule : compiledRegexCache) {
            String repl = forMinecraft ? rule.replacementMinecraft : rule.replacementDiscord;
            try {
                result = safeReplaceAll(rule.pattern, result, repl);
            } catch (Exception e) {
                plugin.log("[DEBUG] Regex rule failed: " + rule.pattern.pattern() + " - " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Performs a regex replacement with timeout protection against ReDoS attacks.
     * Uses an interruptible approach to prevent catastrophic backtracking from
     * blocking.
     */
    private String safeReplaceAll(Pattern pattern, String input, String replacement) {
        final String[] resultHolder = { input };
        final Thread workerThread = new Thread(() -> {
            try {
                // Don't use quoteReplacement - filter.yml regex rules may contain
                // backreferences like $0
                resultHolder[0] = pattern.matcher(input).replaceAll(replacement);
            } catch (Exception ignored) {
            }
        });

        workerThread.start();
        try {
            workerThread.join(REGEX_TIMEOUT_MS);
            if (workerThread.isAlive()) {
                workerThread.interrupt();
                plugin.log("[WARNING] Regex pattern timed out (potential ReDoS): " + pattern.pattern());
                return input; // Return original input on timeout
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return input;
        }
        return resultHolder[0];
    }

    public void runProxyLeaveMessage(String playerName, UUID playerUUID, String serverName,
            BiConsumer<String, Permission> minecraftLogger) {
        String configString = config.get(ConfigKey.MINECRAFT_LEAVE).asString();
        String discordConfigString = config.get(ConfigKey.DISCORD_LEAVE_MESSAGE).asString();

        String aliasedServerName = Helper.convertAlias(config, serverName);
        String timeString = messageFormatter.getTimeString();

        // Build replacements using MessageFormatter
        MessageFormatter.ReplacementBuilder builder = messageFormatter.builder()
                .withPlayer(playerName)
                .withServer(aliasedServerName, serverName)
                .withTime(timeString)
                .withPluginPrefix();

        String message = replacePrefixSuffix(builder.apply(configString), playerUUID, aliasedServerName, serverName);
        String discordMessage = replacePrefixSuffix(builder.apply(discordConfigString), playerUUID, aliasedServerName,
                serverName);

        // Log to Console
        if (config.get(ConfigKey.CONSOLE_LEAVE).asBoolean())
            plugin.log(message);

        // Log to Discord
        DISCORD_SENT: if (config.get(ConfigKey.DISCORD_LEAVE_ENABLED).asBoolean()) {
            if (!config.get(ConfigKey.DISCORD_LEAVE_USE_EMBED).asBoolean()) {
                String overrideId = resolveOverrideChannelId(MessageType.LEAVE);
                discordBot.sendMessageToChannelId(overrideId, discordMessage);
                break DISCORD_SENT;
            }

            EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordMessage).setColor(Color.RED);
            if (config.get(ConfigKey.DISCORD_LEAVE_USE_TIMESTAMP).asBoolean())
                embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
            String overrideId = resolveOverrideChannelId(MessageType.LEAVE);
            discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_LEAVE_ENABLED).asBoolean()
                && config.get(ConfigKey.MINECRAFT_LEAVE_PROXY_SEND).asBoolean()) {
            minecraftLogger.accept(message, Permission.READ_LEAVE_MESSAGE);
        }
    }

    public void runProxyJoinMessage(String playerName, UUID playerUUID, String serverName,
            BiConsumer<String, Permission> minecraftLogger) {

        String aliasedServerName = Helper.convertAlias(config, serverName);
        String timeString = messageFormatter.getTimeString();

        // Build replacements using MessageFormatter
        MessageFormatter.ReplacementBuilder builder = messageFormatter.builder()
                .withPlayer(playerName)
                .withServer(aliasedServerName, serverName)
                .withTime(timeString)
                .withPluginPrefix();

        // Check for first-time join FIRST - if it's a first join, only send first-join
        // messages
        boolean isFirstJoin = plugin.getFirstJoinTracker().isFirstJoin(playerUUID);

        if (isFirstJoin) {
            sendFirstJoinAnnouncement(playerName, playerUUID, aliasedServerName, serverName, builder, minecraftLogger);
            return; // Don't send regular join message for first-time players
        }

        // Regular join message (only for non-first-time players)
        String configString = config.get(ConfigKey.MINECRAFT_JOIN).asString();
        String discordConfigString = config.get(ConfigKey.DISCORD_JOIN_MESSAGE).asString();

        String message = replacePrefixSuffix(builder.apply(configString), playerUUID, aliasedServerName, serverName);
        String discordMessage = replacePrefixSuffix(builder.apply(discordConfigString), playerUUID, aliasedServerName,
                serverName);

        // Log to Console
        if (config.get(ConfigKey.CONSOLE_JOIN).asBoolean())
            plugin.log(message);

        // Log to Discord
        DISCORD_SENT: if (config.get(ConfigKey.DISCORD_JOIN_ENABLED).asBoolean()) {
            if (!config.get(ConfigKey.DISCORD_JOIN_USE_EMBED).asBoolean()) {
                String overrideId = resolveOverrideChannelId(MessageType.JOIN);
                discordBot.sendMessageToChannelId(overrideId, discordMessage);
                break DISCORD_SENT;
            }

            EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordMessage).setColor(Color.GREEN);
            if (config.get(ConfigKey.DISCORD_JOIN_USE_TIMESTAMP).asBoolean())
                embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
            String overrideId = resolveOverrideChannelId(MessageType.JOIN);
            discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_JOIN_ENABLED).asBoolean()
                && config.get(ConfigKey.MINECRAFT_JOIN_PROXY_SEND).asBoolean()) {
            minecraftLogger.accept(message, Permission.READ_JOIN_MESSAGE);
        }
    }

    private void sendFirstJoinAnnouncement(String playerName, UUID playerUUID, String aliasedServerName,
            String serverName, MessageFormatter.ReplacementBuilder builder,
            BiConsumer<String, Permission> minecraftLogger) {
        // Send to Minecraft
        if (config.get(ConfigKey.MINECRAFT_FIRST_JOIN_ENABLED).asBoolean()) {
            String firstJoinTemplate = config.get(ConfigKey.MINECRAFT_FIRST_JOIN_MESSAGE).asString();
            String firstJoinMessage = replacePrefixSuffix(builder.apply(firstJoinTemplate), playerUUID,
                    aliasedServerName, serverName);

            if (config.get(ConfigKey.CONSOLE_JOIN).asBoolean())
                plugin.log("[First Join] " + firstJoinMessage);

            if (config.get(ConfigKey.MINECRAFT_JOIN_PROXY_SEND).asBoolean()) {
                minecraftLogger.accept(firstJoinMessage, Permission.READ_JOIN_MESSAGE);
            }
        }

        // Send to Discord
        if (config.get(ConfigKey.DISCORD_FIRST_JOIN_ENABLED).asBoolean()) {
            String discordFirstJoinTemplate = config.get(ConfigKey.DISCORD_FIRST_JOIN_MESSAGE).asString();
            String discordFirstJoinMessage = replacePrefixSuffix(builder.apply(discordFirstJoinTemplate), playerUUID,
                    aliasedServerName, serverName);

            String overrideId = resolveOverrideChannelId(MessageType.JOIN);

            if (config.get(ConfigKey.DISCORD_JOIN_USE_EMBED).asBoolean()) {
                EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordFirstJoinMessage)
                        .setColor(Color.CYAN);
                if (config.get(ConfigKey.DISCORD_JOIN_USE_TIMESTAMP).asBoolean()) {
                    embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
                }
                discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
            } else {
                discordBot.sendMessageToChannelId(overrideId, discordFirstJoinMessage);
            }
        }
    }

    public void runProxySwitchMessage(String from, String to, String playerName, UUID playerUUID,
            Consumer<String> minecraftLogger, Consumer<String> playerLogger) {
        String consoleConfigString = config.get(ConfigKey.MINECRAFT_SWITCH_DEFAULT).asString();
        String discordConfigString = config.get(ConfigKey.DISCORD_SWITCH_MESSAGE).asString();
        String minecraftConfigString = config.get(ConfigKey.MINECRAFT_SWITCH_SHORT).asString();

        String aliasedFrom = Helper.convertAlias(config, from);
        String aliasedTo = Helper.convertAlias(config, to);
        String timeString = messageFormatter.getTimeString();

        // Build replacements using MessageFormatter
        MessageFormatter.ReplacementBuilder builder = messageFormatter.builder()
                .withFromServer(aliasedFrom, from)
                .withServer(aliasedTo, to)
                .withPlayer(playerName)
                .withTime(timeString)
                .withPluginPrefix();

        String consoleMessage = replacePrefixSuffix(builder.apply(consoleConfigString), playerUUID, aliasedTo, to);
        String discordMessage = replacePrefixSuffix(builder.apply(discordConfigString), playerUUID, aliasedTo, to);
        String minecraftMessage = replacePrefixSuffix(builder.apply(minecraftConfigString), playerUUID, aliasedTo, to);

        // Log to Console
        if (config.get(ConfigKey.CONSOLE_SWITCH).asBoolean())
            plugin.log(consoleMessage);

        // Log to Discord
        DISCORD_SENT: if (config.get(ConfigKey.DISCORD_SWITCH_ENABLED).asBoolean()) {
            if (!config.get(ConfigKey.DISCORD_SWITCH_USE_EMBED).asBoolean()) {
                String overrideId = resolveOverrideChannelId(MessageType.SWITCH);
                discordBot.sendMessageToChannelId(overrideId, discordMessage);
                break DISCORD_SENT;
            }

            EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordMessage).setColor(Color.YELLOW);
            if (config.get(ConfigKey.DISCORD_SWITCH_USE_TIMESTAMP).asBoolean())
                embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
            String overrideId = resolveOverrideChannelId(MessageType.SWITCH);
            discordBot.sendMessageEmbedToChannelId(overrideId, embedBuilder.build());
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_SWITCH_ENABLED).asBoolean()) {
            if (config.get(ConfigKey.MINECRAFT_SWITCH_PROXY_SEND).asBoolean()) {
                minecraftLogger.accept(minecraftMessage);
            }
            lastMessagesHelper.getBoundedArrayList().forEach(playerLogger);
        }
    }

    /**
     * Creates a sanitized {@link EmbedBuilder} based on the message.
     * 
     * @param playerUUID The {@link UUID} of the in-game player.
     * @param message    The {@link String} message to send in the Discord server.
     * @return A sanitized {@link EmbedBuilder} containing the contents.
     */
    private EmbedBuilder simpleAuthorEmbedBuilder(UUID playerUUID, String message) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setAuthor(message, null, getPlayerHeadURL(playerUUID));
        return embedBuilder;
    }

    private String getPlayerHeadURL(UUID playerUUID) {
        return MINECRAFT_PLAYER_HEAD_URL.replace("{PLAYER_UUID}", playerUUID.toString());
    }

    /*
     * Classifies a Discord attachment into a user-friendly label
     * based on content type and file extension.
     * Returns: GIF, Video, Image, Audio, or File
     */
    private String getAttachmentTypeLabel(net.dv8tion.jda.api.entities.Message.Attachment attachment) {
        String contentType = attachment.getContentType();
        String fileName = attachment.getFileName().toLowerCase();

        // GIF detection (check extension first for animated GIFs)
        if (fileName.endsWith(".gif") || (contentType != null && contentType.equalsIgnoreCase("image/gif"))) {
            return "GIF";
        }

        // Video detection
        if (attachment.isVideo() || (contentType != null && contentType.startsWith("video/"))) {
            return "Video";
        }

        // Image detection (after GIF check)
        if (attachment.isImage() || (contentType != null && contentType.startsWith("image/"))) {
            return "Image";
        }

        // Audio detection
        if (contentType != null && contentType.startsWith("audio/")) {
            return "Audio";
        }

        // File extension fallback for common types
        if (fileName.endsWith(".mp4") || fileName.endsWith(".webm") || fileName.endsWith(".mov") ||
                fileName.endsWith(".avi") || fileName.endsWith(".mkv")) {
            return "Video";
        }
        if (fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".ogg") ||
                fileName.endsWith(".flac") || fileName.endsWith(".m4a")) {
            return "Audio";
        }
        if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                fileName.endsWith(".webp") || fileName.endsWith(".bmp")) {
            return "Image";
        }

        return "File";
    }

    // Resolve per-message-type override channel ID from config, or null if not set
    private String resolveOverrideChannelId(MessageType type) {
        try {
            Map<String, String> map = config.get(ConfigKey.DISCORD_CHANNEL_OVERRIDE).asStringMap();
            if (map == null || map.isEmpty())
                return null;
            String key;
            switch (type) {
                case CHAT -> key = "chat";
                case JOIN -> key = "join";
                case LEAVE -> key = "leave";
                case SWITCH -> key = "switch";
                case ADVANCEMENT -> key = "advancement";
                case DEATH -> key = "death";
                default -> key = null;
            }
            if (key == null)
                return null;
            String id = map.getOrDefault(key, null);
            if (id == null)
                return null;
            id = id.trim();
            return id.isEmpty() ? null : id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void sendFromDiscord(MessageReceivedEvent event) {
        String message = config.get(ConfigKey.DISCORD_CHAT_MINECRAFT_MESSAGE).asString();

        if (event.getMember() == null)
            return;

        String username = event.getMember().getUser().getName();
        String nickname = event.getMember().getNickname();
        String displayName = event.getMember().getEffectiveName();

        if (nickname == null)
            nickname = username;

        String roleName = "[no-role]";
        Color roleColor = Color.GRAY;
        if (!event.getMember().getRoles().isEmpty()) {
            Role role = event.getMember().getRoles().get(0);
            roleName = role.getName();

            if (role.getColor() != null)
                roleColor = role.getColor();
        }

        String discordMessage = event.getMessage().getContentStripped();
        String textPart = discordMessage == null ? "" : discordMessage.trim();
        String textMc = "";
        if (!textPart.isEmpty()) {
            textMc = applyRegexRules(applyFilter(textPart), true);
        }

        List<net.dv8tion.jda.api.entities.Message.Attachment> attachments = event.getMessage().getAttachments();
        String attachmentsMc = "";
        if (attachments != null && !attachments.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < attachments.size(); i++) {
                net.dv8tion.jda.api.entities.Message.Attachment attachment = attachments.get(i);
                String url = attachment.getUrl();
                String escapedUrl = url.replace("\"", "\\\"");
                String label = getAttachmentTypeLabel(attachment);
                if (attachments.size() > 1) {
                    label = label + " " + (i + 1);
                }
                if (i > 0)
                    sb.append(" ");
                sb.append(String.format(
                        "<click:open_url:\"%s\"><hover:show_text:\"Click to open attachment\"><dark_gray>[</dark_gray><aqua>%s<dark_gray>]</hover></click>",
                        escapedUrl, label));
            }
            if (!textMc.isEmpty()) {
                attachmentsMc = (attachments.size() > 1 ? "\n" : " ") + sb;
            } else {
                attachmentsMc = sb.toString();
            }
        }

        String discordToMc = textMc + attachmentsMc;

        String hex = "#" + Integer.toHexString(roleColor.getRGB()).substring(2);

        // Build Discord tag (clickable if invite URL configured)
        String discordInvite = config.get(ConfigKey.DISCORD_INVITE_URL).asString();
        String discordTag;
        if (discordInvite != null && !discordInvite.trim().isEmpty()) {
            String inviteEscaped = discordInvite.replace("\"", "\\\"");
            discordTag = String.format(
                    "<click:open_url:\"%s\"><hover:show_text:\"Join our Discord\"><dark_gray>[</dark_gray><aqua>Discord</aqua><dark_gray>]</hover></click>",
                    inviteEscaped);
        } else {
            // Fallback to plain colored tag if no invite is set
            discordTag = "&8[&bDiscord&8]";
        }

        message = Helper.replaceKeys(
                message,
                Tuple.of("role", String.format("<%s>%s</%s>", hex, roleName, hex)),
                Tuple.of("user", username),
                Tuple.of("nick", nickname),
                Tuple.of("display_name", displayName),
                Tuple.of("message", discordToMc),
                Tuple.of("discord-tag", discordTag),
                Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())),
                Tuple.of("time", messageFormatter.getTimeString()),
                Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));

        if (config.get(ConfigKey.MINECRAFT_DISCORD_ENABLED).asBoolean())
            plugin.sendAll(message);
    }

    private List<String> getPrefixBasedOnServerContext(User user, String... serverKeys) {
        return user.resolveInheritedNodes(QueryOptions.nonContextual())
                .stream()
                .filter((node) -> {
                    if (!node.getContexts().containsKey("server"))
                        return true;
                    for (String key : serverKeys)
                        if (node.getContexts().contains("server", key))
                            return true;
                    return false;
                })
                .filter(Node::getValue)
                .filter(NodeType.PREFIX::matches)
                .map(NodeType.PREFIX::cast)
                .map(PrefixNode::getKey)
                .map(prefix -> prefix.replace("prefix.", "")) // 200.Owner.is.awesome
                .map(prefix -> prefix.split("\\.")) // [200, Owner, is, awesome]
                .sorted((left, right) -> { // Sorting it properly.
                    try {
                        Integer leftWeight = Integer.parseInt(left[0]);
                        Integer rightWeight = Integer.parseInt(right[0]);

                        return rightWeight.compareTo(leftWeight);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .map(prefix -> Arrays.stream(prefix).skip(1).collect(Collectors.joining("."))) // Owner.is.awesome
                .toList();
    }

    private List<String> getSuffixBasedOnServerContext(User user, String... serverKeys) {
        return user.resolveInheritedNodes(QueryOptions.nonContextual())
                .stream()
                .filter((node) -> {
                    if (!node.getContexts().containsKey("server"))
                        return true;
                    for (String key : serverKeys)
                        if (node.getContexts().contains("server", key))
                            return true;
                    return false;
                })
                .filter(Node::getValue)
                .filter(NodeType.SUFFIX::matches)
                .map(NodeType.SUFFIX::cast)
                .map(SuffixNode::getKey)
                .map(suffix -> suffix.replace("suffix.", "")) // 200.Owner.is.awesome
                .map(suffix -> suffix.split("\\.")) // [200, Owner, is, awesome]
                .sorted((left, right) -> { // Sorting it properly.
                    try {
                        Integer leftWeight = Integer.parseInt(left[0]);
                        Integer rightWeight = Integer.parseInt(right[0]);

                        return rightWeight.compareTo(leftWeight);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .map(suffix -> Arrays.stream(suffix).skip(1).collect(Collectors.joining("."))) // Owner.is.awesome
                .toList();
    }

    private String replacePrefixSuffix(String message, UUID playerUUID, String aliasedServerName, String serverName) {
        if (!this.plugin.isLuckPermsEnabled())
            return message;

        return this.plugin.getLuckPerms().map(LuckPerms.class::cast).map((luckPerms) -> {
            User user;
            try {
                // Use a short timeout to prevent blocking the main thread
                // LuckPerms caches online players, so this should be fast for them
                user = luckPerms.getUserManager().loadUser(playerUUID)
                        .orTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .join();
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                    plugin.log("[DEBUG] LuckPerms prefix/suffix lookup timed out for " + playerUUID);
                } else {
                    plugin.log("Error contacting the LuckPerms API: " + e.getMessage());
                }
                return message;
            } catch (Exception e) {
                plugin.log("Error contacting the LuckPerms API: " + e.getMessage());
                return message;
            }

            // Get prefix based on aliased name. If none show up, use original name. If none
            // show up, use top prefix.
            List<String> prefixList = getPrefixBasedOnServerContext(user, serverName, aliasedServerName, "");
            List<String> suffixList = getSuffixBasedOnServerContext(user, serverName, aliasedServerName, "");

            String prefix = prefixList.isEmpty() ? "" : Helper.translateLegacyCodes(prefixList.get(0));
            String suffix = suffixList.isEmpty() ? "" : Helper.translateLegacyCodes(suffixList.get(0));

            return message.replace("%prefix%", prefix).replace("%suffix%", suffix);
        }).orElse(message);
    }

}
