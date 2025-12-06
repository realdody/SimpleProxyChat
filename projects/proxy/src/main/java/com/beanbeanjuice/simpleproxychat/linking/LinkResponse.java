package com.beanbeanjuice.simpleproxychat.linking;

/**
 * Encapsulates the response from the account linking API.
 * Used by both link requests (code generation) and status checks.
 */
public class LinkResponse {

    private final boolean success;
    private final String code;
    private final String redeemUrl;
    private final String error;

    private LinkResponse(boolean success, String code, String redeemUrl, String error) {
        this.success = success;
        this.code = code;
        this.redeemUrl = redeemUrl;
        this.error = error;
    }

    public static LinkResponse success(String code, String redeemUrl) {
        return new LinkResponse(true, code, redeemUrl, null);
    }

    public static LinkResponse error(String error) {
        return new LinkResponse(false, null, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getRedeemUrl() {
        return redeemUrl;
    }

    public String getError() {
        return error;
    }

}
