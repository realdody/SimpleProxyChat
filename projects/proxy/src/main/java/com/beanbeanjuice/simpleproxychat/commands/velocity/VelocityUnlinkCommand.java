package com.beanbeanjuice.simpleproxychat.commands.velocity;

import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.linking.LinkApiClient;
import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.config.Permission;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Command for unlinking Minecraft accounts from Discord.
 * Usage: /unlink [confirm|cancel]
 */
public class VelocityUnlinkCommand implements SimpleCommand {

    private final SimpleProxyChatVelocity plugin;
    private final Config config;
    private final LinkApiClient apiClient;

    public VelocityUnlinkCommand(SimpleProxyChatVelocity plugin, LinkApiClient apiClient) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.apiClient = apiClient;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            String message = config.get(ConfigKey.MINECRAFT_COMMAND_MUST_BE_PLAYER).asString();
            message = Helper.replaceKeys(message,
                    Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(message));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendConfirmPrompt(player);
            return;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("confirm")) {
            String unlinking = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_UNLINKING).asString();
            unlinking = Helper.replaceKeys(unlinking,
                    Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            player.sendMessage(Helper.stringToComponent(unlinking));

            apiClient.unlinkPlayer(player.getUniqueId(), player.getUsername())
                    .thenAccept(ok -> {
                        if (ok) {
                            String success = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_SUCCESS).asString();
                            success = Helper.replaceKeys(success,
                                    Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
                            player.sendMessage(Helper.stringToComponent(success));

                            String hint = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_SUCCESS_HINT).asString();
                            Component hintComponent = Helper.stringToComponent(hint)
                                    .clickEvent(ClickEvent.runCommand("/spc-link"))
                                    .hoverEvent(HoverEvent.showText(Component.text("Generate a new link code")));
                            player.sendMessage(hintComponent);
                        } else {
                            String error = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_ERROR).asString();
                            error = Helper.replaceKeys(error,
                                    Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
                            player.sendMessage(Helper.stringToComponent(error));
                        }
                    })
                    .exceptionally(ex -> {
                        plugin.getLogger().error("Failed to unlink account for " + player.getUsername(), ex);
                        String error = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_ERROR).asString();
                        error = Helper.replaceKeys(error,
                                Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
                        player.sendMessage(Helper.stringToComponent(error));
                        return null;
                    });
            return;
        }

        if (sub.equals("cancel")) {
            String cancelled = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_CANCELLED).asString();
            cancelled = Helper.replaceKeys(cancelled,
                    Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            player.sendMessage(Helper.stringToComponent(cancelled));
            return;
        }

        sendConfirmPrompt(player);
    }

    private void sendConfirmPrompt(Player player) {
        player.sendMessage(Component.empty());

        String header = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_CONFIRM_HEADER).asString();
        player.sendMessage(Helper.stringToComponent(header));

        String title = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_CONFIRM_TITLE).asString();
        player.sendMessage(Helper.stringToComponent(title));

        player.sendMessage(Component.empty());

        String text = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_CONFIRM_TEXT).asString();
        player.sendMessage(Helper.stringToComponent(text));

        String prompt = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_CONFIRM_PROMPT).asString();
        player.sendMessage(Helper.stringToComponent(prompt));

        player.sendMessage(Component.empty());

        Component confirm = Component.text("[Confirm]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/spc-unlink confirm"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to unlink your account")));
        Component cancel = Component.text("[Cancel]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/spc-unlink cancel"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to cancel")));
        player.sendMessage(Component.text("  ").append(confirm).append(Component.text("  ")).append(cancel));

        player.sendMessage(Helper.stringToComponent(header));

        player.sendMessage(Component.empty());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if (!config.get(ConfigKey.USE_PERMISSIONS).asBoolean()) {
            return true;
        }
        return invocation.source().hasPermission(Permission.COMMAND_UNLINK.getPermissionNode());
    }

}
