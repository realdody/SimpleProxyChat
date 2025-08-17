package com.beanbeanjuice.simpleproxychat.socket.velocity;

import cc.unilock.yeplib.api.event.YepAdvancementEvent;
import cc.unilock.yeplib.api.event.YepDeathEvent;
import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType;
import com.beanbeanjuice.simpleproxychat.utility.listeners.velocity.VelocityServerListener;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Listens for YepLib events when the YepLib plugin is installed and
 * forwards them into SimpleProxyChat's existing ChatHandler pipeline.
 *
 * If YepLib is not present, this class is never registered.
 */
public class YepLibListener {
    private final SimpleProxyChatVelocity plugin;
    private final VelocityServerListener serverListener;

    public YepLibListener(SimpleProxyChatVelocity plugin, VelocityServerListener serverListener) {
        this.plugin = plugin;
        this.serverListener = serverListener;
    }

    @Subscribe
    public void onDeath(YepDeathEvent event) {
        // Ensure player and source server are available
        Player player = event.getPlayer();
        if (player == null) return;
        RegisteredServer server = event.getSource().getServer();
        if (server == null) return;

        String display = event.getDisplayName() != null ? event.getDisplayName() : event.getUsername();
        String deathMsg = event.getMessage();

        // Resolve server names and config
        Config config = plugin.getSPCConfig();
        String originalServer = server.getServerInfo().getName();
        String aliasedServer = Helper.convertAlias(config, originalServer);

        // Prepare replacements
        List<Tuple<String, String>> repl = new ArrayList<>();
        repl.add(Tuple.of("player", display));
        repl.add(Tuple.of("death_message", deathMsg));
        repl.add(Tuple.of("server", aliasedServer));
        repl.add(Tuple.of("original_server", originalServer));

        // Build messages from config templates
        String mcTpl = config.get(ConfigKey.MINECRAFT_YEP_DEATH_MESSAGE).asString();
        String dcTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_MESSAGE).asString();
        String dcEmbedTitleTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_TITLE).asString();
        String dcEmbedDescTpl = config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_MESSAGE).asString();

        String mcRaw = Helper.replaceKeys(mcTpl, repl);
        String dcRaw = Helper.replaceKeys(dcTpl, repl);
        String dcEmbedTitle = Helper.replaceKeys(dcEmbedTitleTpl, repl);
        String dcEmbedDesc = Helper.replaceKeys(dcEmbedDescTpl, repl);

        VelocityChatMessageData messageData = new VelocityChatMessageData(
                plugin,
                MessageType.DEATH,
                server,
                player,
                deathMsg,
                mcRaw,
                dcRaw,
                dcEmbedTitle,
                dcEmbedDesc
        );

        // Pass through ChatHandler. Translate legacy codes if present; MiniMessage tags are unaffected.
        serverListener.getChatHandler().chat(
                messageData,
                Helper.translateLegacyCodes(mcRaw),
                Helper.translateLegacyCodes(dcRaw),
                Helper.translateLegacyCodes(dcEmbedTitle),
                Helper.translateLegacyCodes(dcEmbedDesc)
        );
    }

    @Subscribe
    public void onAdvancement(YepAdvancementEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        RegisteredServer server = event.getSource().getServer();
        if (server == null) return;

        String display = event.getDisplayName() != null ? event.getDisplayName() : event.getUsername();
        String title = event.getTitle();
        String description = event.getDescription();
        String type = event.getAdvType() != null ? event.getAdvType().name() : "";

        // Resolve server names and config
        Config config = plugin.getSPCConfig();
        String originalServer = server.getServerInfo().getName();
        String aliasedServer = Helper.convertAlias(config, originalServer);
        String typeDecoration = type.isEmpty() ? "" : (":" + type.toLowerCase());

        // Prepare replacements
        List<Tuple<String, String>> repl = new ArrayList<>();
        repl.add(Tuple.of("player", display));
        repl.add(Tuple.of("title", title));
        repl.add(Tuple.of("description", description));
        repl.add(Tuple.of("advancement_type", typeDecoration));
        repl.add(Tuple.of("server", aliasedServer));
        repl.add(Tuple.of("original_server", originalServer));

        // Build messages from config templates
        String mcTpl = config.get(ConfigKey.MINECRAFT_YEP_ADVANCEMENT_MESSAGE).asString();
        String dcTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_MESSAGE).asString();
        String dcEmbedTitleTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_TITLE).asString();
        String dcEmbedDescTpl = config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_MESSAGE).asString();

        String mcRaw = Helper.replaceKeys(mcTpl, repl);
        String dcRaw = Helper.replaceKeys(dcTpl, repl);
        String dcEmbedTitle = Helper.replaceKeys(dcEmbedTitleTpl, repl);
        String dcEmbedDesc = Helper.replaceKeys(dcEmbedDescTpl, repl);

        VelocityChatMessageData messageData = new VelocityChatMessageData(
                plugin,
                MessageType.ADVANCEMENT,
                server,
                player,
                String.format("%s - %s", title, description),
                mcRaw,
                dcRaw,
                dcEmbedTitle,
                dcEmbedDesc
        );

        serverListener.getChatHandler().chat(
                messageData,
                Helper.translateLegacyCodes(mcRaw),
                Helper.translateLegacyCodes(dcRaw),
                Helper.translateLegacyCodes(dcEmbedTitle),
                Helper.translateLegacyCodes(dcEmbedDesc)
        );
    }
}
