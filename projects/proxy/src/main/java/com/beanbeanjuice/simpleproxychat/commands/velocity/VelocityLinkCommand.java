package com.beanbeanjuice.simpleproxychat.commands.velocity;

import com.beanbeanjuice.simpleproxychat.SimpleProxyChatVelocity;
import com.beanbeanjuice.simpleproxychat.linking.LinkService;
import com.beanbeanjuice.simpleproxychat.utility.Tuple;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class VelocityLinkCommand implements SimpleCommand {

    private final SimpleProxyChatVelocity plugin;
    private final Config config;

    public VelocityLinkCommand(final SimpleProxyChatVelocity plugin) {
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
            String err = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ERROR).asString();
            err = Helper.replaceKeys(err, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(err));
            return;
        }

        UUID uuid = player.getUniqueId();
        if (linkService.isMinecraftLinked(uuid)) {
            String already = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ALREADY_LINKED).asString();
            already = Helper.replaceKeys(already, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(already));
            return;
        }

        Optional<String> codeOpt = linkService.generateCode(uuid, player.getUsername());
        if (codeOpt.isEmpty()) {
            String err = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_ERROR).asString();
            err = Helper.replaceKeys(err, Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
            invocation.source().sendMessage(Helper.stringToComponent(err));
            return;
        }

        int minutes;
        try { minutes = config.get(ConfigKey.LINKING_EXPIRATION_MINUTES).asInt(); } catch (Throwable t) { minutes = 10; }

        String message = config.get(ConfigKey.MINECRAFT_COMMAND_LINK_CODE).asString();
        List<Tuple<String, String>> repl = new ArrayList<>();
        repl.add(Tuple.of("code", codeOpt.get()));
        repl.add(Tuple.of("minutes", Integer.toString(minutes)));
        repl.add(Tuple.of("plugin-prefix", config.get(ConfigKey.PLUGIN_PREFIX).asString()));
        message = Helper.replaceKeys(message, repl);
        invocation.source().sendMessage(Helper.stringToComponent(message));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Everyone may link their own account
        return true;
    }
}
