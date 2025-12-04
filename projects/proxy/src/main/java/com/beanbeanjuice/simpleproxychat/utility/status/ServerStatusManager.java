package com.beanbeanjuice.simpleproxychat.utility.status;

import com.beanbeanjuice.simpleproxychat.discord.Bot;
import com.beanbeanjuice.simpleproxychat.utility.ISimpleProxyChat;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.*;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.function.Consumer;

public class ServerStatusManager {

    private final ISimpleProxyChat plugin;
    private final Config config;
    private final Hashtable<String, ServerStatus> servers = new Hashtable<>(); // Hashtable for Thread Safety

    public ServerStatusManager(ISimpleProxyChat plugin) {
        this.plugin = plugin;
        this.config = plugin.getSPCConfig();
    }

    private int getDebounceThreshold() {
        return config.get(ConfigKey.SERVER_STATUS_DEBOUNCE_THRESHOLD).asInt();
    }

    public ServerStatus getStatus(String serverName) {
        servers.putIfAbsent(serverName, new ServerStatus(getDebounceThreshold()));
        return servers.get(serverName);
    }

    public void setStatus(String serverName, ServerState state) {
        servers.put(serverName, new ServerStatus(state, getDebounceThreshold()));
    }

    /**
     * Gets the server's current state.
     * 
     * @param serverName The server name.
     * @return The server state (UNKNOWN, ONLINE, or OFFLINE).
     */
    public ServerState getServerState(String serverName) {
        return getStatus(serverName).getState();
    }

    /**
     * Checks if a server is currently online.
     * 
     * @param serverName The server name.
     * @return true if ONLINE, false otherwise.
     */
    public boolean isServerOnline(String serverName) {
        return getStatus(serverName).getState() == ServerState.ONLINE;
    }

    public MessageEmbed getStatusEmbed(String serverName, boolean status) {
        String statusMessageString = config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_MESSAGE).asString();
        String statusString = status ? config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_ONLINE).asString()
                : config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_OFFLINE).asString();

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle(config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_TITLE).asString());
        embedBuilder.addField(
                Helper.convertAlias(config, serverName),
                String.format("%s%s", statusMessageString, statusString),
                false);
        embedBuilder.setColor(status ? Color.GREEN : Color.RED);
        return embedBuilder.build();
    }

    public MessageEmbed getAllStatusEmbed() {
        String title = config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_TITLE).asString();
        String message = config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_MESSAGE).asString();
        String onlineString = config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_ONLINE).asString();
        String offlineString = config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_OFFLINE).asString();

        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle(title);

        servers.forEach((serverName, serverStatus) -> {
            String statusString = (serverStatus.getStatus()) ? onlineString : offlineString;
            embedBuilder.addField(Helper.convertAlias(config, serverName), message + statusString, true);
        });

        embedBuilder.setColor(Color.YELLOW);
        return embedBuilder.build();
    }

    public String getStatusString(String serverName, boolean status) {
        String statusString = status ? "online" : "offline";
        return String.format("%s is %s.", Helper.convertAlias(config, serverName), statusString);
    }

    public ArrayList<String> getAllStatusStrings() {
        ArrayList<String> statusStrings = new ArrayList<>();
        if (config.get(ConfigKey.CONSOLE_SERVER_STATUS).asBoolean())
            servers.forEach((serverName, serverStatus) -> statusStrings
                    .add(getStatusString(serverName, serverStatus.getStatus())));
        return statusStrings;
    }

    public void runStatusLogic(String serverName, boolean newStatus, Bot discordBot, Consumer<String> logger) {
        if (plugin.isPluginStarting()) {
            this.setStatus(serverName, newStatus ? ServerState.ONLINE : ServerState.OFFLINE);
            return;
        }

        ServerStatus currentStatus = this.getStatus(serverName);
        currentStatus.updateStatus(newStatus).ifPresent((state) -> {
            boolean isOnline = state == ServerState.ONLINE;
            if (config.get(ConfigKey.DISCORD_PROXY_STATUS_ENABLED).asBoolean())
                discordBot.sendMessageEmbed(this.getStatusEmbed(serverName, isOnline));

            if (config.get(ConfigKey.CONSOLE_SERVER_STATUS).asBoolean())
                logger.accept(Helper.sanitize(this.getStatusString(serverName, isOnline)));
        });
    }

    /**
     * Instantly confirms a server as online (event-driven detection).
     * Use this when a player successfully connects to a server.
     * 
     * @param serverName The server name.
     * @param discordBot The Discord bot for notifications.
     * @param logger     Logger consumer for console output.
     */
    public void confirmServerOnline(String serverName, Bot discordBot, Consumer<String> logger) {
        if (plugin.isPluginStarting()) {
            this.setStatus(serverName, ServerState.ONLINE);
            return;
        }

        ServerStatus currentStatus = this.getStatus(serverName);
        currentStatus.confirmOnline().ifPresent((state) -> {
            if (config.get(ConfigKey.DISCORD_PROXY_STATUS_ENABLED).asBoolean())
                discordBot.sendMessageEmbed(this.getStatusEmbed(serverName, true));

            if (config.get(ConfigKey.CONSOLE_SERVER_STATUS).asBoolean())
                logger.accept(Helper.sanitize(this.getStatusString(serverName, true)));
        });
    }
}
