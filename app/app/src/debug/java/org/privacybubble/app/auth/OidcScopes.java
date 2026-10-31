package org.privacybubble.app.auth;

/** Exact token response scopes for the specific request made by OidcBrowserFlow. */
final class OidcScopes {
    private OidcScopes() { }

    static boolean matchesInitial(Object scope, boolean synthetic, boolean consentScope) {
        // The server may omit scope when it is identical to the requested scope.
        if (scope == null) return true;
        if (!(scope instanceof String)) return false;
        if (synthetic) return RotatingSession.syntheticScope((String) scope, consentScope);
        return "openid".equals(scope);
    }
}
