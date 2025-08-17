package com.beanbeanjuice.simpleproxychat.utility.mojang;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojangResolver {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    private MojangResolver() {}

    /**
     * Resolve a Minecraft username to its UUID via Mojang API.
     * @param username Minecraft username (case-insensitive)
     * @return Optional UUID if found
     */
    public static Optional<UUID> resolveUuid(final String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                    .timeout(Duration.ofSeconds(7))
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().isBlank()) return Optional.empty();
            Matcher m = ID_PATTERN.matcher(resp.body());
            if (!m.find()) return Optional.empty();
            String hex32 = m.group(1);
            String dashed = hex32.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
            return Optional.of(UUID.fromString(dashed));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
