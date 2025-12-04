package com.beanbeanjuice.simpleproxychat.discord;

import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DiscordSlashCommandHandler extends ListenerAdapter {

    private final Config config;
    private final Supplier<Map<String, List<String>>> getVisiblePlayersByServer;
    // Additional suppliers for advanced formatting
    private final Supplier<Set<String>> getAllServerNames;
    private final Supplier<Map<String, Boolean>> getServerOnlineMap;
    private final Supplier<Integer> getProxyMaxPlayers;

    public DiscordSlashCommandHandler(
            final Config config,
            final Supplier<Map<String, List<String>>> getVisiblePlayersByServer,
            final Supplier<Set<String>> getAllServerNames,
            final Supplier<Map<String, Boolean>> getServerOnlineMap,
            final Supplier<Integer> getProxyMaxPlayers
    ) {
        this.config = config;
        this.getVisiblePlayersByServer = getVisiblePlayersByServer;
        this.getAllServerNames = getAllServerNames;
        this.getServerOnlineMap = getServerOnlineMap;
        this.getProxyMaxPlayers = getProxyMaxPlayers;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        final String cmd = event.getName();

        // Channel restriction for all commands
        String configuredChannelId = null;
        try { configuredChannelId = config.get(ConfigKey.CHANNEL_ID).asString(); } catch (Exception ignored) {}
        if (configuredChannelId != null && !configuredChannelId.isBlank() &&
                !event.getChannel().getId().equals(configuredChannelId)) {
            event.reply("Please use this command in <#" + configuredChannelId + ">.").setEphemeral(true).queue();
            return;
        }

        // Handle /list
        if (!"list".equalsIgnoreCase(cmd)) return;

        // Enabled toggle
        boolean enabled = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_ENABLED).asBoolean()).orElse(false);
        if (!enabled) {
            event.reply("This command is disabled.").setEphemeral(true).queue();
            return;
        }

        // Role check (IDs or names). If empty, allow everyone.
        List<String> tempAllowed = null;
        try {
            tempAllowed = config.get(ConfigKey.DISCORD_COMMAND_LIST_ALLOWED_ROLES).asList();
        } catch (Exception ignored) {}
        final List<String> allowedRoles = tempAllowed != null ? tempAllowed : Collections.emptyList();
        if (event.getMember() != null && !allowedRoles.isEmpty()) {
            final Set<String> allowedRoleIds = new HashSet<>(allowedRoles);
            final Set<String> allowedRoleNamesLower = allowedRoles.stream().map(String::toLowerCase).collect(Collectors.toSet());
            boolean hasRole = event.getMember().getRoles().stream().anyMatch(r ->
                    allowedRoleIds.contains(r.getId()) || allowedRoleNamesLower.contains(r.getName().toLowerCase())
            );
            if (!hasRole) {
                event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
                return;
            }
        }

        boolean ephemeral = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_EPHEMERAL).asBoolean()).orElse(true);

        Map<String, List<String>> byServer = Optional.ofNullable(getVisiblePlayersByServer.get()).orElseGet(Collections::emptyMap);
        int total = byServer.values().stream().mapToInt(List::size).sum();

        // Prefer advanced codeblock/templated formatting when server-format is provided; otherwise fallback
        String serverFormat = null;
        try { serverFormat = config.get(ConfigKey.DISCORD_COMMAND_LIST_SERVER_FORMAT).asString(); } catch (Exception ignored) {}
        if (serverFormat != null && !serverFormat.isEmpty()) {
            String codeblockLang = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_CODEBLOCK_LANG).asString()).orElse("");
            String playerFormat = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_PLAYER_FORMAT).asString()).orElse("- %username%");
            String noPlayersFormat = null;
            try { noPlayersFormat = config.get(ConfigKey.DISCORD_COMMAND_LIST_NO_PLAYERS_FORMAT).asString(); } catch (Exception ignored) {}
            String serverOfflineFormat = null;
            try { serverOfflineFormat = config.get(ConfigKey.DISCORD_COMMAND_LIST_SERVER_OFFLINE_FORMAT).asString(); } catch (Exception ignored) {}

            final Set<String> allServers = Optional.ofNullable(getAllServerNames != null ? getAllServerNames.get() : null)
                    .orElseGet(() -> new HashSet<>(byServer.keySet()));
            final Map<String, Boolean> onlineMap = Optional.ofNullable(getServerOnlineMap != null ? getServerOnlineMap.get() : null)
                    .orElseGet(Collections::emptyMap);
            final int proxyMax = Optional.ofNullable(getProxyMaxPlayers != null ? getProxyMaxPlayers.get() : null).orElse(0);

            final String codeOpen = "```" + codeblockLang + "\n";
            final String codeClose = "```";
            final int limit = 1950; // safe headroom under 2000 incl. code fences
            StringBuilder chunk = new StringBuilder(codeOpen);
            List<String> out = new ArrayList<>();

            // Apply disabled-servers filter consistent with other features
            final List<String> disabled = Optional.ofNullable(config.get(ConfigKey.DISABLED_SERVERS).asList()).orElseGet(Collections::emptyList);

            // Ensure captured variables are effectively final for lambda usage
            final Map<String, List<String>> byServerLocal = byServer;
            final Map<String, Boolean> onlineMapLocal = onlineMap;
            final int pMax = proxyMax;
            final String sf = serverFormat;
            final String pf = playerFormat;
            final String npf = noPlayersFormat;
            final String sof = serverOfflineFormat;
            final List<String> disabledLocal = disabled;

            allServers.stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(name -> {
                if (disabledLocal.contains(name)) return;
                final String displayName = Helper.convertAlias(config, name);
                final List<String> players = byServerLocal.getOrDefault(name, Collections.emptyList())
                        .stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
                final boolean isOnline = Optional.ofNullable(onlineMapLocal.get(name)).orElse(true);
                final int onlinePlayers = players.size();
                final int maxPlayers = pMax; // per-server max unknown; use proxy max as best-effort

                String headerLine = sf
                        .replace("%server_name%", displayName)
                        .replace("%online_players%", String.valueOf(onlinePlayers))
                        .replace("%max_players%", String.valueOf(maxPlayers));
                String toAppend = headerLine + '\n';
                if (chunk.length() + toAppend.length() + codeClose.length() > limit) {
                    chunk.append(codeClose);
                    out.add(chunk.toString());
                    chunk.setLength(0);
                    chunk.append(codeOpen);
                }
                chunk.append(toAppend);

                if (!isOnline && sof != null && !sof.isEmpty()) {
                    toAppend = sof + '\n';
                    if (chunk.length() + toAppend.length() + codeClose.length() > limit) {
                        chunk.append(codeClose);
                        out.add(chunk.toString());
                        chunk.setLength(0);
                        chunk.append(codeOpen);
                    }
                    chunk.append(toAppend);
                } else if (onlinePlayers == 0 && npf != null && !npf.isEmpty()) {
                    toAppend = npf + '\n';
                    if (chunk.length() + toAppend.length() + codeClose.length() > limit) {
                        chunk.append(codeClose);
                        out.add(chunk.toString());
                        chunk.setLength(0);
                        chunk.append(codeOpen);
                    }
                    chunk.append(toAppend);
                } else {
                    for (String username : players) {
                        String userLine = pf.replace("%username%", username) + '\n';
                        if (chunk.length() + userLine.length() + codeClose.length() > limit) {
                            chunk.append(codeClose);
                            out.add(chunk.toString());
                            chunk.setLength(0);
                            chunk.append(codeOpen);
                        }
                        chunk.append(userLine);
                    }
                }
                // blank line separator
                String blank = "\n";
                if (chunk.length() + blank.length() + codeClose.length() > limit) {
                    chunk.append(codeClose);
                    out.add(chunk.toString());
                    chunk.setLength(0);
                    chunk.append(codeOpen);
                }
                chunk.append(blank);
            });

            // finalize and send
            if (chunk.length() > codeOpen.length()) {
                chunk.append(codeClose);
                out.add(chunk.toString());
            }
            if (out.isEmpty()) {
                out.add(codeOpen + codeClose);
            }
            event.deferReply().setEphemeral(ephemeral).queue(hook -> {
                hook.editOriginal(out.get(0)).queue();
                for (int i = 1; i < out.size(); i++) hook.sendMessage(out.get(i)).queue();
            });
            return;
        }

        // Fallback to simple header/per-server list
        String none = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_NONE).asString()).orElse("No players online.");
        if (total == 0) {
            event.reply(none).setEphemeral(ephemeral).queue();
            return;
        }

        String header = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_HEADER).asString()).orElse("Online (%total%):");
        header = header.replace("%total%", String.valueOf(total));

        String perServerFmt = Optional.ofNullable(config.get(ConfigKey.DISCORD_COMMAND_LIST_PER_SERVER).asString()).orElse("**%server%** (%count%): %players%");

        final int limit = 1950;
        List<String> out = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        // Add header first
        if (header.length() >= limit) {
            out.add(header.substring(0, Math.min(header.length(), limit - 1)));
            chunk.setLength(0);
        } else {
            chunk.append(header).append('\n');
        }

        byServer.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).forEach(server -> {
            List<String> players = byServer.getOrDefault(server, Collections.emptyList())
                    .stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
            String line = perServerFmt
                    .replace("%server%", server)
                    .replace("%count%", String.valueOf(players.size()))
                    .replace("%players%", players.isEmpty() ? "-" : String.join(", ", players));
            String toAppend = line + '\n';
            if (toAppend.length() > limit) {
                // Split very long lines
                int idx = 0;
                while (idx < toAppend.length()) {
                    int end = Math.min(idx + limit, toAppend.length());
                    String part = toAppend.substring(idx, end);
                    if (chunk.length() + part.length() > limit) {
                        out.add(chunk.toString());
                        chunk.setLength(0);
                    }
                    chunk.append(part);
                    if (chunk.length() >= limit) {
                        out.add(chunk.toString());
                        chunk.setLength(0);
                    }
                    idx = end;
                }
            } else {
                if (chunk.length() + toAppend.length() > limit) {
                    out.add(chunk.toString());
                    chunk.setLength(0);
                }
                chunk.append(toAppend);
            }
        });
        if (chunk.length() > 0) out.add(chunk.toString());
        if (out.isEmpty()) out.add(none);

        event.deferReply().setEphemeral(ephemeral).queue(hook -> {
            hook.editOriginal(out.get(0)).queue();
            for (int i = 1; i < out.size(); i++) hook.sendMessage(out.get(i)).queue();
        });
    }
}
