package com.beanbeanjuice.simpleproxychat.commands.velocity;

import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.linking.LinkApiClient;
import com.beanbeanjuice.simpleproxychat.linking.LinkResponse;
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

import java.util.concurrent.CompletableFuture;

/**
 * Command for linking Minecraft accounts to Discord via external API.
 * Usage: /link [relink]
 */
public class VelocityLinkCommand implements SimpleCommand {

    private final SimpleProxyChatVelocity plugin;
    private final Config config;
    private final LinkApiClient apiClient;

    public VelocityLinkCommand(SimpleProxyChatVelocity plugin, LinkApiClient apiClient) {
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
        boolean relinkRequested = args.length > 0 && args[0].equalsIgnoreCase("relink");

        apiClient.isUsernameLinked(player.getUsername())
                .thenCompose(alreadyLinked -> {
                    if (alreadyLinked && !relinkRequested) {
                        sendAlreadyLinkedPrompt(player);
                        return CompletableFuture.completedFuture((LinkResponse) null);
                    }
                    String generating = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_GENERATING).asString();
                    generating = Helper.replaceKeys(generating,
                            Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
                    player.sendMessage(Helper.stringToComponent(generating));
                    return apiClient.requestLinkCode(player.getUniqueId(), player.getUsername());
                })
                .thenAccept(response -> {
                    if (response == null)
                        return;
                    if (response.isSuccess()) {
                        sendSuccessMessage(player, response.getCode(), response.getRedeemUrl());
                    } else {
                        String error = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ERROR).asString();
                        error = Helper.replaceKeys(error,
                                Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()),
                                Tuple.of("error", response.getError()));
                        player.sendMessage(Helper.stringToComponent(error));
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().error("Failed to process link command for " + player.getUsername(), ex);
                    String error = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ERROR).asString();
                    error = Helper.replaceKeys(error,
                            Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()),
                            Tuple.of("error", "An error occurred. Please try again later."));
                    player.sendMessage(Helper.stringToComponent(error));
                    return null;
                });
    }

    private void sendSuccessMessage(Player player, String code, String url) {
        player.sendMessage(Component.empty());

        String header = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_HEADER).asString();
        player.sendMessage(Helper.stringToComponent(header));

        String title = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_TITLE).asString();
        player.sendMessage(Helper.stringToComponent(title));

        player.sendMessage(Component.empty());

        String codeMsg = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_CODE).asString();
        codeMsg = Helper.replaceKeys(codeMsg, Tuple.of("code", code));
        player.sendMessage(Helper.stringToComponent(codeMsg));

        player.sendMessage(Component.empty());

        String steps = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_STEPS).asString();
        player.sendMessage(Helper.stringToComponent(steps));

        String step1 = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_STEP1).asString();
        step1 = Helper.replaceKeys(step1, Tuple.of("url", url));
        Component step1Component = Helper.stringToComponent(step1)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open")));
        player.sendMessage(step1Component);

        String step2 = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_STEP2).asString();
        player.sendMessage(Helper.stringToComponent(step2));

        String step3 = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_STEP3).asString();
        step3 = Helper.replaceKeys(step3, Tuple.of("code", code));
        player.sendMessage(Helper.stringToComponent(step3));

        player.sendMessage(Component.empty());

        Component relink = Component.text("[Relink]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/spc-link relink"))
                .hoverEvent(HoverEvent.showText(Component.text("Generate a new link code")));
        Component unlink = Component.text("[Unlink]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/spc-unlink"))
                .hoverEvent(HoverEvent.showText(Component.text("Unlink your account")));
        player.sendMessage(Component.text("  ").append(relink).append(Component.text("  ")).append(unlink));

        player.sendMessage(Component.empty());

        String expires = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_EXPIRES).asString();
        player.sendMessage(Helper.stringToComponent(expires));

        String footer = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_FOOTER).asString();
        player.sendMessage(Helper.stringToComponent(footer));

        player.sendMessage(Component.empty());
    }

    private void sendAlreadyLinkedPrompt(Player player) {
        player.sendMessage(Component.empty());

        String header = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ALREADY_LINKED_HEADER).asString();
        player.sendMessage(Helper.stringToComponent(header));

        String title = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ALREADY_LINKED_TITLE).asString();
        player.sendMessage(Helper.stringToComponent(title));

        player.sendMessage(Component.empty());

        String prompt = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ALREADY_LINKED_PROMPT).asString();
        player.sendMessage(Helper.stringToComponent(prompt));

        player.sendMessage(Component.empty());

        Component relink = Component.text("[Relink]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/spc-link relink"))
                .hoverEvent(HoverEvent.showText(Component.text("Generate a new link code")));
        Component unlink = Component.text("[Unlink]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/spc-unlink"))
                .hoverEvent(HoverEvent.showText(Component.text("Unlink your account")));
        player.sendMessage(Component.text("  ").append(relink).append(Component.text("  ")).append(unlink));

        String footer = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_SUCCESS_HEADER).asString();
        player.sendMessage(Helper.stringToComponent(footer));

        player.sendMessage(Component.empty());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        if (!config.get(ConfigKey.USE_PERMISSIONS).asBoolean()) {
            return true;
        }
        return invocation.source().hasPermission(Permission.COMMAND_LINK.getPermissionNode());
    }

}
