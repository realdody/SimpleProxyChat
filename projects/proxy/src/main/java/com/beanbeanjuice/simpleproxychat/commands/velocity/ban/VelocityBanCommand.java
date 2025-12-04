package com.beanbeanjuice.simpleproxychat.commands.velocity.ban;

import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.config.Permission;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.mojang.MojangResolver;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class VelocityBanCommand implements SimpleCommand {

    // Valid Minecraft username pattern: 3-16 alphanumeric characters or underscores
    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private final SimpleProxyChatVelocity plugin;
    private final Config config;

    public VelocityBanCommand(final SimpleProxyChatVelocity plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!config.get(ConfigKey.USE_SIMPLE_PROXY_CHAT_BANNING_SYSTEM).asBoolean()) {
            invocation.source().sendMessage(Helper.stringToComponent("&cThe banning system is disabled..."));
            return;
        }

        if (invocation.arguments().length != 1) {
            String errorMessage = config.get(ConfigKey.MINECRAFT_COMMAND_PROXY_BAN_USAGE).asString();
            invocation.source().sendMessage(Helper.stringToComponent(errorMessage));
            return;
        }

        String playerName = invocation.arguments()[0];

        // Input validation
        if (!VALID_USERNAME.matcher(playerName).matches()) {
            invocation.source().sendMessage(Helper
                    .stringToComponent("&cInvalid username format. Usernames must be 3-16 alphanumeric characters."));
            return;
        }

        // Check if player is currently online - get UUID directly
        Optional<Player> onlinePlayer = plugin.getProxyServer().getPlayer(playerName);

        if (onlinePlayer.isPresent()) {
            // Player is online - ban immediately using their UUID
            Player player = onlinePlayer.get();
            UUID playerUUID = player.getUniqueId();
            String actualName = player.getUsername();

            plugin.getBanHelper().addBan(playerUUID, actualName);
            player.disconnect(Helper.stringToComponent("&cYou have been banned from the proxy."));

            sendBannedMessage(invocation, actualName);
        } else {
            // Player is offline - need to resolve UUID from Mojang API
            invocation.source().sendMessage(Helper.stringToComponent("&7Looking up player UUID..."));

            CompletableFuture.supplyAsync(() -> MojangResolver.resolveUuid(playerName))
                    .thenAccept(optionalUUID -> {
                        if (optionalUUID.isPresent()) {
                            UUID playerUUID = optionalUUID.get();
                            plugin.getBanHelper().addBan(playerUUID, playerName);

                            plugin.getProxyServer().getScheduler()
                                    .buildTask(plugin, () -> sendBannedMessage(invocation, playerName)).schedule();
                        } else {
                            plugin.getProxyServer().getScheduler()
                                    .buildTask(plugin, () -> invocation.source().sendMessage(Helper.stringToComponent(
                                            "&cCould not find player '" + playerName
                                                    + "'. Make sure the username is correct.")))
                                    .schedule();
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getProxyServer().getScheduler()
                                .buildTask(plugin, () -> invocation.source().sendMessage(Helper.stringToComponent(
                                        "&cError looking up player: " + ex.getMessage())))
                                .schedule();
                        return null;
                    });
        }
    }

    private void sendBannedMessage(Invocation invocation, String playerName) {
        String bannedMessage = config.get(ConfigKey.MINECRAFT_COMMAND_PROXY_BAN_BANNED).asString();
        bannedMessage = Helper.replaceKeys(
                bannedMessage,
                Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()),
                Tuple.of("player", playerName));
        invocation.source().sendMessage(Helper.stringToComponent(bannedMessage));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length == 0) {
            return plugin.getProxyServer().getAllPlayers().stream().map(Player::getUsername).toList();
        }

        if (invocation.arguments().length == 1) {
            return plugin.getProxyServer().getAllPlayers()
                    .stream()
                    .map(Player::getUsername)
                    .filter((name) -> name.toLowerCase().startsWith(invocation.arguments()[0].toLowerCase()))
                    .toList();
        }

        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(Permission.COMMAND_BAN.getPermissionNode());
    }
}
