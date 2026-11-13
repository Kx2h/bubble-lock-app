package org.privacybubble.app.auth;

import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;

public final class MobileApiAuthorization {
    public final String contract;
    public final String apiScope;

    private MobileApiAuthorization(String contract, String apiScope) {
        this.contract = contract;
        this.apiScope = apiScope;
    }

    public static MobileApiAuthorization forContract(String contract) {
        if ("keyrings-v1".equals(contract)) return new MobileApiAuthorization(contract, "location:read");
        if ("portal-central-v1".equals(contract) || "portal-web-v1".equals(contract))
            return new MobileApiAuthorization(contract, "portal:read");
        throw new IllegalArgumentException("Unsupported mobile API contract");
    }

    public String requestScope() { return "openid " + (contract.equals("keyrings-v1") ? "offline_access " : "") + apiScope; }

    public Set<String> allowedScopes() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(requestScope().split(" "))));
    }

    public void requireResponseScope(String value) {
        if (value == null || value.isEmpty() || value.length() > 128 || value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ')
            throw new IllegalArgumentException("Invalid mobile authorization scope");
        Set<String> received = new HashSet<>();
        Set<String> allowed = allowedScopes();
        for (String scope : value.split(" ", -1)) {
            if (!allowed.contains(scope) || !received.add(scope))
                throw new IllegalArgumentException("Invalid mobile authorization scope");
        }
        if (!received.contains(apiScope)) throw new IllegalArgumentException("Required mobile authorization scope missing");
    }
}
