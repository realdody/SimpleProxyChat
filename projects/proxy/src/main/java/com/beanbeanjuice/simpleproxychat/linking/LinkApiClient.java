package com.beanbeanjuice.simpleproxychat.linking;

import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the account linking API.
 * Handles code generation, unlinking, and link status verification.
 */
public class LinkApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final Config config;
    private final Logger logger;

    public LinkApiClient(Config config, Logger logger) {
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
        this.config = config;
        this.logger = logger;
    }

    /**
     * Requests a new link code from the API for the given player.
     *
     * @param playerUuid     The player's UUID
     * @param playerUsername The player's username
     * @return A future containing the link response
     */
    public CompletableFuture<LinkResponse> requestLinkCode(UUID playerUuid, String playerUsername) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("minecraft_uuid", playerUuid.toString());
                requestBody.addProperty("minecraft_username", playerUsername);

                String apiKey = config.get(ConfigKey.LINKING_API_KEY).asString();
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBody.addProperty("api_key", apiKey);
                }

                RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);

                Request request = new Request.Builder()
                        .url(getApiUrl() + "/minecraft/link/request")
                        .post(body)
                        .header("Content-Type", "application/json")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.warn("Link API request failed with status: " + response.code());
                        return LinkResponse.error("API returned status: " + response.code());
                    }

                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        return LinkResponse.error("Empty response from API");
                    }

                    JsonObject jsonResponse = gson.fromJson(responseBody.string(), JsonObject.class);

                    if (!jsonResponse.has("ok") || !jsonResponse.get("ok").getAsBoolean()) {
                        String error = jsonResponse.has("error")
                                ? jsonResponse.get("error").getAsString()
                                : "Unknown error";
                        return LinkResponse.error(error);
                    }

                    String code = jsonResponse.get("code").getAsString();
                    String redeemUrl = jsonResponse.has("redeem_url")
                            ? jsonResponse.get("redeem_url").getAsString()
                            : getApiUrl() + "/link?code=" + code;

                    return LinkResponse.success(code, redeemUrl);
                }
            } catch (IOException e) {
                logger.error("Failed to request link code from API", e);
                return LinkResponse.error("Network error: " + e.getMessage());
            }
        });
    }

    /**
     * Unlinks the player's Minecraft account from their Discord account.
     *
     * @param playerUuid     The player's UUID
     * @param playerUsername The player's username
     * @return A future containing true if unlink succeeded
     */
    public CompletableFuture<Boolean> unlinkPlayer(UUID playerUuid, String playerUsername) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("minecraft_uuid", playerUuid.toString());
                requestBody.addProperty("minecraft_username", playerUsername);

                String apiKey = config.get(ConfigKey.LINKING_API_KEY).asString();
                if (apiKey != null && !apiKey.isEmpty()) {
                    requestBody.addProperty("api_key", apiKey);
                }

                RequestBody body = RequestBody.create(gson.toJson(requestBody), JSON);
                Request request = new Request.Builder()
                        .url(getApiUrl() + "/minecraft/link/unlink")
                        .post(body)
                        .header("Content-Type", "application/json")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        logger.warn("Unlink request failed with status: " + response.code());
                        return false;
                    }
                    ResponseBody responseBody = response.body();
                    if (responseBody == null)
                        return false;
                    JsonObject json = gson.fromJson(responseBody.string(), JsonObject.class);
                    return json.has("ok") && json.get("ok").getAsBoolean();
                }
            } catch (IOException e) {
                logger.error("Failed to unlink player:", e);
                return false;
            }
        });
    }

    /**
     * Checks if the given Minecraft username is already linked to a Discord
     * account.
     *
     * @param playerUsername The username to check
     * @return A future containing true if linked
     */
    public CompletableFuture<Boolean> isUsernameLinked(String playerUsername) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encoded = URLEncoder.encode(playerUsername, StandardCharsets.UTF_8);
                String url = getApiUrl() + "/minecraft/link/verify/" + encoded;

                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.code() == 404) {
                        return false;
                    }
                    if (!response.isSuccessful()) {
                        logger.warn("Verify request failed with status: " + response.code());
                        return false;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        return false;
                    }
                    JsonObject json = gson.fromJson(body.string(), JsonObject.class);
                    boolean ok = json.has("ok") && json.get("ok").getAsBoolean();
                    boolean linked = json.has("linked") && json.get("linked").getAsBoolean();
                    return ok && linked;
                }
            } catch (IOException e) {
                logger.error("Failed to verify link status for username: " + playerUsername, e);
                return false;
            }
        });
    }

    private String getApiUrl() {
        return config.get(ConfigKey.LINKING_API_URL).asString();
    }

}
