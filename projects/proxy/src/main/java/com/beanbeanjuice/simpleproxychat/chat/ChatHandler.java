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
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

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

    public ChatHandler(ISimpleProxyChat plugin) {
        this.plugin = plugin;
        this.config = plugin.getSPCConfig();
        this.discordBot = plugin.getDiscordBot();
        this.webhookSender = new WebhookSender(plugin, this.config);
        this.lastMessagesHelper = new LastMessagesHelper(plugin.getSPCConfig());

        plugin.getDiscordBot().addRunnableToQueue(() -> plugin.getDiscordBot().getJDA().ifPresent((jda) -> jda.addEventListener(new DiscordChatHandler(config, this::sendFromDiscord))));
    }

    private Optional<String> getValidMessage(String message) {
        String messagePrefix = config.get(ConfigKey.PROXY_MESSAGE_PREFIX).asString();
        String messagePrefixBlacklist = config.get(ConfigKey.PROXY_MESSAGE_PREFIX_BLACKLIST).asString();

        if (!messagePrefixBlacklist.isEmpty() && message.startsWith(messagePrefixBlacklist)) return Optional.empty();

        if (messagePrefix.isEmpty()) return Optional.of(message);
        if (!message.startsWith(messagePrefix)) return Optional.empty();

        message = message.substring(messagePrefix.length());
        if (message.isEmpty()) return Optional.empty();
        return Optional.of(message);
    }

    public void chat(ChatMessageData chatMessageData, String minecraftMessage, String discordMessage, String discordEmbedTitle, String discordEmbedMessage) {
        // Log to Console
        if (config.get(ConfigKey.CONSOLE_CHAT).asBoolean()) plugin.log(minecraftMessage);

        // Log to Discord
        if (config.get(ConfigKey.MINECRAFT_DISCORD_ENABLED).asBoolean()) {
            // Events (advancement/death) can be routed to a separate webhook regardless of global mode
            boolean sentToEventsWebhook = false;
            if (chatMessageData.getType() == MessageType.ADVANCEMENT &&
                    config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_WEBHOOK_SEND).asBoolean()) {
                String originalServer = chatMessageData.getServername();
                String aliasedServer = Helper.convertAlias(config, originalServer);
                // Resolve events webhook (single URL)
                String eventsUrl = java.util.Optional.ofNullable(config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_URL).asString()).orElse("");

                if (eventsUrl != null && !eventsUrl.isBlank()) {
                    webhookSender.sendEventEmbed(
                            chatMessageData.getPlayerUUID(),
                            chatMessageData.getPlayerName(),
                            aliasedServer,
                            originalServer,
                            MessageType.ADVANCEMENT,
                            discordEmbedTitle,
                            discordEmbedMessage,
                            null
                    );
                } else {
                    // Fallback to bot embed (no webhooks)
                    java.awt.Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();
                    EmbedBuilder embedBuilder = new EmbedBuilder().setColor(color);

                    // Respect messages.yml advancement embed toggles
                    boolean useAuthor = false; // default off for advancement
                    boolean useAuthorIcon = true;
                    try { useAuthor = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_AUTHOR).asBoolean(); } catch (Throwable ignored) {}
                    try { useAuthorIcon = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_USE_AUTHOR_ICON).asBoolean(); } catch (Throwable ignored) {}

                    // Title/Description from passed-in values (already formatted for event)
                    String _title = java.util.Optional.ofNullable(discordEmbedTitle).orElse("").trim();
                    String _desc = java.util.Optional.ofNullable(discordEmbedMessage).orElse("").trim();
                    if (!_title.isEmpty()) embedBuilder.setTitle(_title);
                    if (!_desc.isEmpty()) embedBuilder.setDescription(_desc);

                    if (useAuthor) {
                        String authorTextTpl = null;
                        try { authorTextTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_AUTHOR_TEXT).asString(); } catch (Throwable ignored) {}
                        String authorText;
                        if (authorTextTpl != null && !authorTextTpl.isBlank()) {
                            authorText = authorTextTpl
                                    .replace("%player%", chatMessageData.getPlayerName())
                                    .replace("%server%", aliasedServer == null ? "" : Helper.escapeString(aliasedServer))
                                    .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer))
                                    .replace("%title%", _title)
                                    .replace("%description%", _desc);
                            authorText = Helper.sanitize(authorText).trim();
                        } else {
                            authorText = "\u200B"; // ensure author row exists so icon can render
                        }
                        String authorIcon = null;
                        if (useAuthorIcon) {
                            String iconTpl = null;
                            try { iconTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_AUTHOR_ICON_URL).asString(); } catch (Throwable ignored) {}
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
                    discordBot.sendMessageEmbed(embedBuilder.build());
                }
                sentToEventsWebhook = true;
            } else if (chatMessageData.getType() == MessageType.DEATH &&
                    config.get(ConfigKey.DISCORD_YEP_DEATH_WEBHOOK_SEND).asBoolean()) {
                String originalServer = chatMessageData.getServername();
                String aliasedServer = Helper.convertAlias(config, originalServer);
                // Resolve events webhook (single URL)
                String eventsUrl = java.util.Optional.ofNullable(config.get(ConfigKey.DISCORD_EVENTS_WEBHOOK_URL).asString()).orElse("");

                if (eventsUrl != null && !eventsUrl.isBlank()) {
                    webhookSender.sendEventEmbed(
                            chatMessageData.getPlayerUUID(),
                            chatMessageData.getPlayerName(),
                            aliasedServer,
                            originalServer,
                            MessageType.DEATH,
                            discordEmbedTitle,
                            discordEmbedMessage,
                            chatMessageData.getMessage()
                    );
                } else {
                    // Fallback to bot embed (no webhooks)
                    java.awt.Color color = config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_COLOR).asColor();
                    EmbedBuilder embedBuilder = new EmbedBuilder().setColor(color);

                    // Respect messages.yml death embed toggles
                    boolean useAuthor = true;
                    boolean useAuthorIcon = true;
                    try { useAuthor = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_AUTHOR).asBoolean(); } catch (Throwable ignored) {}
                    try { useAuthorIcon = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_USE_AUTHOR_ICON).asBoolean(); } catch (Throwable ignored) {}

                    // Title/Description from passed-in values (already formatted for event)
                    String _title = java.util.Optional.ofNullable(discordEmbedTitle).orElse("").trim();
                    String _desc = java.util.Optional.ofNullable(discordEmbedMessage).orElse("").trim();
                    if (!_title.isEmpty()) embedBuilder.setTitle(_title);
                    if (!_desc.isEmpty()) embedBuilder.setDescription(_desc);

                    if (useAuthor) {
                        String authorTextTpl = null;
                        try { authorTextTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_AUTHOR_TEXT).asString(); } catch (Throwable ignored) {}
                        String authorText;
                        if (authorTextTpl != null && !authorTextTpl.isBlank()) {
                            String raw = chatMessageData.getMessage();
                            authorText = authorTextTpl
                                    .replace("%player%", chatMessageData.getPlayerName())
                                    .replace("%server%", aliasedServer == null ? "" : Helper.escapeString(aliasedServer))
                                    .replace("%original_server%", originalServer == null ? "" : Helper.escapeString(originalServer))
                                    .replace("%death_message%", (raw == null || raw.isBlank()) ? _title : raw);
                            authorText = Helper.sanitize(authorText).trim();
                        } else {
                            authorText = "\u200B"; // ensure author row exists so icon can render
                        }
                        String authorIcon = null;
                        if (useAuthorIcon) {
                            String iconTpl = null;
                            try { iconTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_AUTHOR_ICON_URL).asString(); } catch (Throwable ignored) {}
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
                    discordBot.sendMessageEmbed(embedBuilder.build());
                }
                sentToEventsWebhook = true;
            }

            if (!sentToEventsWebhook) {
                String mode = Optional.ofNullable(config.get(ConfigKey.MINECRAFT_DISCORD_MODE).asString())
                        .map(s -> s.toLowerCase(Locale.ROOT)).orElse("plain");
                if ("webhook".equals(mode)) {
                    String originalServer = chatMessageData.getServername();
                    String aliasedServer = Helper.convertAlias(config, originalServer);
                    // Plain webhook message: username = player, avatar = player, content = discordMessage
                    webhookSender.send(
                            chatMessageData.getPlayerUUID(),
                            chatMessageData.getPlayerName(),
                            aliasedServer,
                            originalServer,
                            discordMessage
                    );
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

                discordBot.sendMessageEmbed(embedBuilder.build());
            } else {
                // plain
                discordBot.sendMessage(discordMessage);
                }
            }
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_CHAT_ENABLED).asBoolean()) {
            chatMessageData.chatSendToAllOtherPlayers(minecraftMessage);
            lastMessagesHelper.addMessage(minecraftMessage);
        }

    }

    public void runProxyChatMessage(ChatMessageData chatMessageData) {
        if (Helper.serverHasChatLocked(plugin, chatMessageData.getServername())) return;

        String playerMessage = chatMessageData.getMessage();
        String serverName = chatMessageData.getServername();
        String playerName = chatMessageData.getPlayerName();
        UUID playerUUID = chatMessageData.getPlayerUUID();

        Optional<String> optionalPlayerMessage = getValidMessage(playerMessage);
        if (optionalPlayerMessage.isEmpty()) return;
        playerMessage = optionalPlayerMessage.get();

        // Apply word filter before any linkification or formatting
        playerMessage = applyFilter(playerMessage);

        // Apply regex rules; linkifier removed in favor of regex-based replacements
        String mcMessagePart = applyRegexRules(playerMessage, true);
        String dcMessagePart = applyRegexRules(playerMessage, false);

        String minecraftConfigString = config.get(ConfigKey.MINECRAFT_CHAT_MESSAGE).asString();
        String discordConfigString = config.get(ConfigKey.MINECRAFT_DISCORD_MESSAGE).asString();

        String aliasedServerName = Helper.convertAlias(config, serverName);

        List<Tuple<String, String>> replacementsMinecraft = new ArrayList<>();
        replacementsMinecraft.add(Tuple.of("message", mcMessagePart));
        replacementsMinecraft.add(Tuple.of("server", aliasedServerName));
        replacementsMinecraft.add(Tuple.of("original_server", serverName));
        replacementsMinecraft.add(Tuple.of("to", aliasedServerName));
        replacementsMinecraft.add(Tuple.of("original_to", serverName));
        replacementsMinecraft.add(Tuple.of("player", playerName));
        replacementsMinecraft.add(Tuple.of("escaped_player", Helper.escapeString(playerName)));
        replacementsMinecraft.add(Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())));
        replacementsMinecraft.add(Tuple.of("time", getTimeString()));
        replacementsMinecraft.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));

        List<Tuple<String, String>> replacementsDiscord = new ArrayList<>();
        replacementsDiscord.add(Tuple.of("message", dcMessagePart));
        replacementsDiscord.add(Tuple.of("server", aliasedServerName));
        replacementsDiscord.add(Tuple.of("original_server", serverName));
        replacementsDiscord.add(Tuple.of("to", aliasedServerName));
        replacementsDiscord.add(Tuple.of("original_to", serverName));
        replacementsDiscord.add(Tuple.of("player", playerName));
        replacementsDiscord.add(Tuple.of("escaped_player", Helper.escapeString(playerName)));
        replacementsDiscord.add(Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())));
        replacementsDiscord.add(Tuple.of("time", getTimeString()));
        replacementsDiscord.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));

        String minecraftMessage = Helper.replaceKeys(minecraftConfigString, replacementsMinecraft);
        String discordMessage = Helper.replaceKeys(discordConfigString, replacementsDiscord);
        String discordEmbedTitle = Helper.replaceKeys(config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_TITLE).asString(), replacementsDiscord);
        String discordEmbedMessage = Helper.replaceKeys(config.get(ConfigKey.MINECRAFT_DISCORD_EMBED_MESSAGE).asString(), replacementsDiscord);

        minecraftMessage = replacePrefixSuffix(minecraftMessage, playerUUID, aliasedServerName, serverName);
        discordMessage = replacePrefixSuffix(discordMessage, playerUUID, aliasedServerName, serverName);
        discordEmbedTitle = replacePrefixSuffix(discordEmbedTitle, chatMessageData.getPlayerUUID(), aliasedServerName, chatMessageData.getServername());

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
        if (!config.isFilterEnabled() || text == null || text.isEmpty()) return text;
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
        if (input.isEmpty()) return input;
        String result = input;

        // Build combined replacement map: specific replacements + global words -> default
        Map<String, String> combined = new LinkedHashMap<>();
        Map<String, String> specific = Optional.ofNullable(config.getFilterReplacements()).orElseGet(Collections::emptyMap);
        combined.putAll(specific);
        List<String> globals = Optional.ofNullable(config.getFilterGlobalWords()).orElseGet(Collections::emptyList);
        for (String gw : globals) {
            if (!combined.containsKey(gw)) combined.put(gw, config.getFilterDefaultReplacement());
        }

        if (combined.isEmpty()) return result;

        int flags = 0;
        if (config.isFilterCaseInsensitive()) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

        for (Map.Entry<String, String> e : combined.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty()) continue;
            String replacement = e.getValue() == null ? "" : e.getValue();
            String core = Pattern.quote(key);
            String pattern = config.isFilterWholeWord() ? "\\b" + core + "\\b" : core;
            result = Pattern.compile(pattern, flags).matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    private String applyRegexRules(String text, boolean forMinecraft) {
        List<com.beanbeanjuice.simpleproxychat.utility.config.Config.FilterRegexRule> rules = Optional.ofNullable(config.getFilterRegexRules()).orElseGet(Collections::emptyList);
        if (rules.isEmpty() || text == null || text.isEmpty()) return text;
        String result = text;
        for (com.beanbeanjuice.simpleproxychat.utility.config.Config.FilterRegexRule r : rules) {
            if (r == null || r.pattern == null || r.pattern.isEmpty()) continue;
            String repl = forMinecraft ? r.replacementMinecraft : r.replacementDiscord;
            if (repl == null) repl = "";
            int flags = 0;
            if (r.flags != null) {
                if (r.flags.contains("i")) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                if (r.flags.contains("m")) flags |= Pattern.MULTILINE;
                if (r.flags.contains("s")) flags |= Pattern.DOTALL;
            }
            try {
                result = Pattern.compile(r.pattern, flags).matcher(result).replaceAll(repl);
            } catch (Exception ignored) { }
        }
        return result;
    }

    public void runProxyLeaveMessage(String playerName, UUID playerUUID, String serverName,
                                     BiConsumer<String, Permission> minecraftLogger) {
        String configString = config.get(ConfigKey.MINECRAFT_LEAVE).asString();
        String discordConfigString = config.get(ConfigKey.DISCORD_LEAVE_MESSAGE).asString();

        String aliasedServerName = Helper.convertAlias(config, serverName);

        List<Tuple<String, String>> replacements = new ArrayList<>();
        replacements.add(Tuple.of("player", playerName));
        replacements.add(Tuple.of("escaped_player", Helper.escapeString(playerName)));
        replacements.add(Tuple.of("server", aliasedServerName));
        replacements.add(Tuple.of("original_server", serverName));
        replacements.add(Tuple.of("to", aliasedServerName));
        replacements.add(Tuple.of("original_to", serverName));
        replacements.add(Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())));
        replacements.add(Tuple.of("time", getTimeString()));
        replacements.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));

        String message = Helper.replaceKeys(configString, replacements);
        String discordMessage = Helper.replaceKeys(discordConfigString, replacements);

        message = replacePrefixSuffix(message, playerUUID, aliasedServerName, serverName);
        discordMessage = replacePrefixSuffix(discordMessage, playerUUID, aliasedServerName, serverName);

        // Log to Console
        if (config.get(ConfigKey.CONSOLE_LEAVE).asBoolean()) plugin.log(message);

        // Log to Discord
        DISCORD_SENT: if (config.get(ConfigKey.DISCORD_LEAVE_ENABLED).asBoolean()) {
            if (!config.get(ConfigKey.DISCORD_LEAVE_USE_EMBED).asBoolean()) {
                discordBot.sendMessage(discordMessage);
                break DISCORD_SENT;
            }

            EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordMessage).setColor(Color.RED);
            if (config.get(ConfigKey.DISCORD_LEAVE_USE_TIMESTAMP).asBoolean()) embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
            discordBot.sendMessageEmbed(embedBuilder.build());
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_LEAVE_ENABLED).asBoolean()) minecraftLogger.accept(message, Permission.READ_LEAVE_MESSAGE);
    }

    public void runProxyJoinMessage(String playerName, UUID playerUUID, String serverName,
                                    BiConsumer<String, Permission> minecraftLogger) {
        String configString = config.get(ConfigKey.MINECRAFT_JOIN).asString();
        String discordConfigString = config.get(ConfigKey.DISCORD_JOIN_MESSAGE).asString();

        String aliasedServerName = Helper.convertAlias(config, serverName);

        List<Tuple<String, String>> replacements = new ArrayList<>();
        replacements.add(Tuple.of("player", playerName));
        replacements.add(Tuple.of("escaped_player", Helper.escapeString(playerName)));
        replacements.add(Tuple.of("server", Helper.convertAlias(config, serverName)));
        replacements.add(Tuple.of("to", Helper.convertAlias(config, serverName)));
        replacements.add(Tuple.of("server", aliasedServerName));
        replacements.add(Tuple.of("original_server", serverName));
        replacements.add(Tuple.of("to", aliasedServerName));
        replacements.add(Tuple.of("original_to", serverName));
        replacements.add(Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())));
        replacements.add(Tuple.of("time", getTimeString()));
        replacements.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));

        String message = Helper.replaceKeys(configString, replacements);
        String discordMessage = Helper.replaceKeys(discordConfigString, replacements);

        message = replacePrefixSuffix(message, playerUUID, aliasedServerName, serverName);
        discordMessage = replacePrefixSuffix(discordMessage, playerUUID, aliasedServerName, serverName);

        // Log to Console
        if (config.get(ConfigKey.CONSOLE_JOIN).asBoolean()) plugin.log(message);

        // Log to Discord
        DISCORD_SENT: if (config.get(ConfigKey.DISCORD_JOIN_ENABLED).asBoolean()) {
            if (!config.get(ConfigKey.DISCORD_JOIN_USE_EMBED).asBoolean()) {
                discordBot.sendMessage(discordMessage);
                break DISCORD_SENT;
            }

            EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordMessage).setColor(Color.GREEN);
            if (config.get(ConfigKey.DISCORD_JOIN_USE_TIMESTAMP).asBoolean()) embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
            discordBot.sendMessageEmbed(embedBuilder.build());
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_JOIN_ENABLED).asBoolean())
            minecraftLogger.accept(message, Permission.READ_JOIN_MESSAGE);
    }

    public void runProxySwitchMessage(String from, String to, String playerName, UUID playerUUID,
                                      Consumer<String> minecraftLogger, Consumer<String> playerLogger) {
        String consoleConfigString = config.get(ConfigKey.MINECRAFT_SWITCH_DEFAULT).asString();
        String discordConfigString = config.get(ConfigKey.DISCORD_SWITCH_MESSAGE).asString();
        String minecraftConfigString = config.get(ConfigKey.MINECRAFT_SWITCH_SHORT).asString();

        String aliasedFrom = Helper.convertAlias(config, from);
        String aliasedTo = Helper.convertAlias(config, to);

        List<Tuple<String, String>> replacements = new ArrayList<>();
        replacements.add(Tuple.of("from", aliasedFrom));
        replacements.add(Tuple.of("original_from", from));
        replacements.add(Tuple.of("to", aliasedTo));
        replacements.add(Tuple.of("original_to", to));
        replacements.add(Tuple.of("server", aliasedTo));
        replacements.add(Tuple.of("original_server", to));
        replacements.add(Tuple.of("player", playerName));
        replacements.add(Tuple.of("escaped_player", Helper.escapeString(playerName)));
        replacements.add(Tuple.of("epoch", String.valueOf(EpochHelper.getEpochSecond())));
        replacements.add(Tuple.of("time", getTimeString()));
        replacements.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));

        String consoleMessage = Helper.replaceKeys(consoleConfigString, replacements);
        String discordMessage = Helper.replaceKeys(discordConfigString, replacements);
        String minecraftMessage = Helper.replaceKeys(minecraftConfigString, replacements);

        consoleMessage = replacePrefixSuffix(consoleMessage, playerUUID, aliasedTo, to);
        minecraftMessage = replacePrefixSuffix(minecraftMessage, playerUUID, aliasedTo, to);
        discordMessage = replacePrefixSuffix(discordMessage, playerUUID, aliasedTo, to);

        // Log to Console
        if (config.get(ConfigKey.CONSOLE_SWITCH).asBoolean()) plugin.log(consoleMessage);

        // Log to Discord
        DISCORD_SENT: if (config.get(ConfigKey.DISCORD_SWITCH_ENABLED).asBoolean()) {
            if (!config.get(ConfigKey.DISCORD_SWITCH_USE_EMBED).asBoolean()) {
                discordBot.sendMessage(discordMessage);
                break DISCORD_SENT;
            }

            EmbedBuilder embedBuilder = simpleAuthorEmbedBuilder(playerUUID, discordMessage).setColor(Color.YELLOW);
            if (config.get(ConfigKey.DISCORD_SWITCH_USE_TIMESTAMP).asBoolean()) embedBuilder.setTimestamp(EpochHelper.getEpochInstant());
            discordBot.sendMessageEmbed(embedBuilder.build());
        }

        // Log to Minecraft
        if (config.get(ConfigKey.MINECRAFT_SWITCH_ENABLED).asBoolean()) {
            minecraftLogger.accept(minecraftMessage);
            lastMessagesHelper.getBoundedArrayList().forEach(playerLogger);
        }
    }

    /**
     * Creates a sanitized {@link EmbedBuilder} based on the message.
     * @param playerUUID The {@link UUID} of the in-game player.
     * @param message The {@link String} message to send in the Discord server.
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

    public void sendFromDiscord(MessageReceivedEvent event) {
        String message = config.get(ConfigKey.DISCORD_CHAT_MINECRAFT_MESSAGE).asString();

        if (event.getMember() == null) return;

        String username = event.getMember().getUser().getName();
        String nickname = event.getMember().getNickname();
        String displayName = event.getMember().getEffectiveName();

        if (nickname == null) nickname = username;

        String roleName = "[no-role]";
        Color roleColor = Color.GRAY;
        if (!event.getMember().getRoles().isEmpty()) {
            Role role = event.getMember().getRoles().get(0);
            roleName = role.getName();

            if (role.getColor() != null) roleColor = role.getColor();
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
                String url = attachments.get(i).getUrl();
                String escapedUrl = url.replace("\"", "\\\"");
                String label = attachments.size() == 1 ? "Attachment" : ("Attachment " + (i + 1));
                if (i > 0) sb.append(" ");
                sb.append(String.format("<click:open_url:\"%s\"><hover:show_text:\"Click to open attachment\"><dark_gray>[</dark_gray><aqua>%s<dark_gray>]</hover></click>", escapedUrl, label));
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
            discordTag = String.format("<click:open_url:\"%s\"><hover:show_text:\"Join our Discord\"><dark_gray>[</dark_gray><aqua>Discord</aqua><dark_gray>]</hover></click>", inviteEscaped);
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
                Tuple.of("time", getTimeString()),
                Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString())
        );

        if (config.get(ConfigKey.MINECRAFT_DISCORD_ENABLED).asBoolean()) plugin.sendAll(message);
    }

    private List<String> getPrefixBasedOnServerContext(User user, String... serverKeys) {
        return user.resolveInheritedNodes(QueryOptions.nonContextual())
                .stream()
                .filter((node) -> {
                    if (!node.getContexts().containsKey("server")) return true;
                    for (String key : serverKeys) if (node.getContexts().contains("server", key)) return true;
                    return false;
                })
                .filter(Node::getValue)
                .filter(NodeType.PREFIX::matches)
                .map(NodeType.PREFIX::cast)
                .map(PrefixNode::getKey)
                .map(prefix -> prefix.replace("prefix.", "")) // 200.Owner.is.awesome
                .map(prefix -> prefix.split("\\."))  // [200, Owner, is, awesome]
                .sorted((left, right) -> {  // Sorting it properly.
                    try {
                        Integer leftWeight = Integer.parseInt(left[0]);
                        Integer rightWeight = Integer.parseInt(right[0]);

                        return rightWeight.compareTo(leftWeight);
                    } catch (NumberFormatException e) { return 0; }
                })
                .map(prefix -> Arrays.stream(prefix).skip(1).collect(Collectors.joining(".")))  // Owner.is.awesome
                .toList();
    }

    private List<String> getSuffixBasedOnServerContext(User user, String... serverKeys) {
        return user.resolveInheritedNodes(QueryOptions.nonContextual())
                .stream()
                .filter((node) -> {
                    if (!node.getContexts().containsKey("server")) return true;
                    for (String key : serverKeys) if (node.getContexts().contains("server", key)) return true;
                    return false;
                })
                .filter(Node::getValue)
                .filter(NodeType.SUFFIX::matches)
                .map(NodeType.SUFFIX::cast)
                .map(SuffixNode::getKey)
                .map(suffix -> suffix.replace("suffix.", "")) // 200.Owner.is.awesome
                .map(suffix -> suffix.split("\\."))  // [200, Owner, is, awesome]
                .sorted((left, right) -> {  // Sorting it properly.
                    try {
                        Integer leftWeight = Integer.parseInt(left[0]);
                        Integer rightWeight = Integer.parseInt(right[0]);

                        return rightWeight.compareTo(leftWeight);
                    } catch (NumberFormatException e) { return 0; }
                })
                .map(suffix -> Arrays.stream(suffix).skip(1).collect(Collectors.joining(".")))  // Owner.is.awesome
                .toList();
    }

    private String replacePrefixSuffix(String message, UUID playerUUID, String aliasedServerName, String serverName) {
        if (!this.plugin.isLuckPermsEnabled()) return message;

        return this.plugin.getLuckPerms().map(LuckPerms.class::cast).map((luckPerms) -> {
            User user = null;
            try {
                user = luckPerms.getUserManager().loadUser(playerUUID).get();
            } catch (Exception e) {
                plugin.log("Error contacting the LuckPerms API: " + e.getMessage());
                return message;
            }

            // Get prefix based on aliased name. If none show up, use original name. If none show up, use top prefix.
            List<String> prefixList = getPrefixBasedOnServerContext(user, serverName, aliasedServerName, "");
            List<String> suffixList = getSuffixBasedOnServerContext(user, serverName, aliasedServerName, "");

            String prefix = prefixList.isEmpty() ? "" : Helper.translateLegacyCodes(prefixList.get(0));
            String suffix = suffixList.isEmpty() ? "" : Helper.translateLegacyCodes(suffixList.get(0));

            return message.replace("%prefix%", prefix).replace("%suffix%", suffix);
        }).orElse(message);
    }

    /**
     * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html">Format</a>
     */
    private String getTimeString() {
        DateTimeZone zone = config.get(ConfigKey.TIMESTAMP_TIMEZONE).asDateTimeZone();
        DateTimeFormatter format = DateTimeFormat.forPattern(config.get(ConfigKey.TIMESTAMP_FORMAT).asString());

        long timeInMillis = EpochHelper.getEpochMillisecond();
        DateTime time = new DateTime(timeInMillis).withZone(zone);

        return time.toString(format);
    }

}
