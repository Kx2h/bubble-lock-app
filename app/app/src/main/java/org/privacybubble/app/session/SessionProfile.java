package org.privacybubble.app.session;

import java.nio.charset.StandardCharsets;
import org.privacybubble.app.auth.MobileApiAuthorization;

public final class SessionProfile {
    private SessionProfile() { }

    public static byte[] associatedData(String contract) {
        String checked = MobileApiAuthorization.forContract(contract).contract;
        return ("org.privacybubble.app/mobile-session/v2/" + checked).getBytes(StandardCharsets.UTF_8);
    }

    public static boolean matchesContract(Object stored, String expected) {
        String checked = MobileApiAuthorization.forContract(expected).contract;
        return stored instanceof String && checked.equals(stored);
    }
}
