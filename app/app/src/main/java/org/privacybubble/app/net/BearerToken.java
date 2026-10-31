package org.privacybubble.app.net;

public final class BearerToken {
    private BearerToken() { }

    public static boolean isValid(String value) {
        return value != null && value.length() <= 8192 && value.matches("[A-Za-z0-9._~+/-]+=*");
    }
}
