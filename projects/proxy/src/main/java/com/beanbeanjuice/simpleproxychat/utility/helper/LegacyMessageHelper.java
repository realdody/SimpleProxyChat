package com.beanbeanjuice.simpleproxychat.utility.helper;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/*
 * Utility class for handling message length limitations on legacy Minecraft clients.
 *
 * Minecraft 1.7.x has a 100-character limit for chat messages. Sending longer
 * messages causes a CorruptedFrameException in Velocity's LegacyChatPacket decoder.
 * This helper detects legacy clients and truncates messages accordingly.
 */
public final class LegacyMessageHelper {

    /*
     * Maximum chat message length for Minecraft 1.7.x clients.
     * Modern clients (1.8+) support 256+ characters.
     */
    private static final int LEGACY_CHAT_LIMIT = 100;

    /*
     * Suffix appended to truncated messages to indicate content was cut.
     */
    private static final String TRUNCATION_SUFFIX = "...";

    private LegacyMessageHelper() {
        /* Static utility class - no instantiation. */
    }

    /*
     * Checks if a player is on a legacy protocol version (pre-1.8).
     *
     * Protocol version 47 corresponds to Minecraft 1.8. Any version below
     * this is considered legacy and subject to the 100-char chat limit.
     *
     * @param player The player to check.
     * 
     * @return True if the player is on protocol < 47 (pre-1.8), false otherwise.
     */
    public static boolean isLegacyPlayer(Player player) {
        ProtocolVersion version = player.getProtocolVersion();
        return version.compareTo(ProtocolVersion.MINECRAFT_1_8) < 0;
    }

    /*
     * Sends a message to a player, truncating for legacy clients if necessary.
     *
     * Modern clients receive the full component. Legacy clients (pre-1.8)
     * receive a plain-text version truncated to 100 characters to avoid
     * CorruptedFrameException in the protocol decoder.
     *
     * @param player The player to send the message to.
     * 
     * @param component The message component to send.
     */
    public static void sendSafeMessage(Player player, Component component) {
        if (!isLegacyPlayer(player)) {
            player.sendMessage(component);
            return;
        }

        /*
         * Legacy path: serialize to plain text, truncate if needed, then
         * send as a simple text component. Formatting is sacrificed to
         * stay within protocol limits.
         */
        String plainText = PlainTextComponentSerializer.plainText().serialize(component);

        if (plainText.length() <= LEGACY_CHAT_LIMIT) {
            player.sendMessage(Component.text(plainText));
            return;
        }

        int cutPoint = LEGACY_CHAT_LIMIT - TRUNCATION_SUFFIX.length();
        String truncated = plainText.substring(0, cutPoint) + TRUNCATION_SUFFIX;
        player.sendMessage(Component.text(truncated));
    }

}
