package com.beanbeanjuice.simpleproxychat.socket.velocity;

import cc.unilock.yeplib.api.event.YepAdvancementEvent;
import cc.unilock.yeplib.api.event.YepDeathEvent;
import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.chat.MessageFormatter;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.listeners.MessageType;
import com.beanbeanjuice.simpleproxychat.utility.listeners.velocity.VelocityServerListener;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Listens for YepLib events when the YepLib plugin is installed and
 * forwards them into SimpleProxyChat's existing ChatHandler pipeline.
 *
 * If YepLib is not present, this class is never registered.
 */
public class YepLibListener {
    private final SimpleProxyChatVelocity plugin;
    private final VelocityServerListener serverListener;
    private final MessageFormatter messageFormatter;

    public YepLibListener(SimpleProxyChatVelocity plugin, VelocityServerListener serverListener) {
        this.plugin = plugin;
        this.serverListener = serverListener;
        this.messageFormatter = new MessageFormatter(plugin.getSPCConfig());
    }

    @Subscribe
    public void onDeath(YepDeathEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        RegisteredServer server = event.getSource().getServer();
        if (server == null) return;

        String display = event.getDisplayName() != null ? event.getDisplayName() : event.getUsername();
        String deathMsg = event.getMessage();

        Config config = plugin.getSPCConfig();
        String originalServer = server.getServerInfo().getName();
        String aliasedServer = Helper.convertAlias(config, originalServer);

        // Build replacements using MessageFormatter
        MessageFormatter.ReplacementBuilder builder = messageFormatter.builder()
                .withPlayer(display)
                .withServer(aliasedServer, originalServer)
                .withDeathMessage(deathMsg);

        String mcRaw = builder.apply(config.get(ConfigKey.MINECRAFT_YEP_DEATH_MESSAGE).asString());
        String dcRaw = builder.apply(config.get(ConfigKey.DISCORD_YEP_DEATH_MESSAGE).asString());
        String dcEmbedTitle = builder.apply(config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_TITLE).asString());
        String dcEmbedDesc = builder.apply(config.get(ConfigKey.DISCORD_YEP_DEATH_EMBED_MESSAGE).asString());

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
        String typeDecoration = type.isEmpty() ? "" : (":" + type.toLowerCase());

        Config config = plugin.getSPCConfig();
        String originalServer = server.getServerInfo().getName();
        String aliasedServer = Helper.convertAlias(config, originalServer);

        // Build replacements using MessageFormatter
        MessageFormatter.ReplacementBuilder builder = messageFormatter.builder()
                .withPlayer(display)
                .withServer(aliasedServer, originalServer)
                .withAdvancement(title, description, typeDecoration);

        String mcRaw = builder.apply(config.get(ConfigKey.MINECRAFT_YEP_ADVANCEMENT_MESSAGE).asString());
        String dcRaw = builder.apply(config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_MESSAGE).asString());
        String dcEmbedTitle = builder.apply(config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_TITLE).asString());
        String dcEmbedDesc = builder.apply(config.get(ConfigKey.DISCORD_YEP_ADVANCEMENT_EMBED_MESSAGE).asString());

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
