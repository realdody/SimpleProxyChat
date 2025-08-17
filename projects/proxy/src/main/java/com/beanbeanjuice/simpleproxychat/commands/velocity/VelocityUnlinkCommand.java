package com.beanbeanjuice.simpleproxychat.commands.velocity;

import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.linking.LinkService;
import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.UUID;

public class VelocityUnlinkCommand implements SimpleCommand {

    private final SimpleProxyChatVelocity plugin;
    private final Config config;

    public VelocityUnlinkCommand(final SimpleProxyChatVelocity plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            String msg = config.get(ConfigKey.MINECRAFT_COMMAND_MUST_BE_PLAYER).asString();
            msg = Helper.replaceKeys(msg, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(msg));
            return;
        }

        LinkService linkService = plugin.getLinkService();
        if (linkService == null || !linkService.isEnabled()) {
            String err = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_ERROR).asString();
            err = Helper.replaceKeys(err, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(err));
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!linkService.isMinecraftLinked(uuid)) {
            String not = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_NOT_LINKED).asString();
            not = Helper.replaceKeys(not, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(not));
            return;
        }

        LinkService.UnlinkResult result = linkService.unlinkMinecraft(uuid);
        String messageKey;
        switch (result) {
            case SUCCESS -> messageKey = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_SUCCESS).asString();
            case NOT_LINKED -> messageKey = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_NOT_LINKED).asString();
            default -> messageKey = config.get(ConfigKey.MINECRAFT_COMMAND_UNLINK_ERROR).asString();
        }
        String out = Helper.replaceKeys(messageKey, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
        invocation.source().sendMessage(Helper.stringToComponent(out));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Everyone may unlink their own account
        return true;
    }
}
