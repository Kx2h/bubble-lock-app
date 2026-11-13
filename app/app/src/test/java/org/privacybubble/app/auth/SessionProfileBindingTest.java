package org.privacybubble.app.auth;

import org.junit.Test;
import org.privacybubble.app.session.SessionProfile;
import java.nio.charset.StandardCharsets;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import static org.junit.Assert.*;

public final class SessionProfileBindingTest {
    private static final String[] CONTRACTS = {"keyrings-v1", "portal-central-v1", "portal-web-v1"};

    @Test public void contractMustBeExplicitAndExactlyMatchEncryptedContext() {
        for (String expected : CONTRACTS) {
            assertTrue(SessionProfile.matchesContract(expected,expected));
            for (Object prior : new Object[]{null,"",42,expected + " ","unsupported"}) assertFalse(SessionProfile.matchesContract(prior,expected));
            for (String prior : CONTRACTS) if (!prior.equals(expected)) assertFalse(SessionProfile.matchesContract(prior,expected));
        }
    }

    @Test public void gcmCiphertextCannotCrossContractsEvenWhenTheOauthScopeIsTheSame() throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES"); generator.init(128); SecretKey key=generator.generateKey();
        byte[] plaintext="{\"access\":\"isolated-context\"}".getBytes(StandardCharsets.UTF_8);
        for (String original : CONTRACTS) {
            Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding"); encrypt.init(Cipher.ENCRYPT_MODE,key);
            encrypt.updateAAD(SessionProfile.associatedData(original)); byte[] encrypted=encrypt.doFinal(plaintext);
            for (String current : CONTRACTS) {
                Cipher decrypt=Cipher.getInstance("AES/GCM/NoPadding"); decrypt.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypt.getIV()));
                decrypt.updateAAD(SessionProfile.associatedData(current));
                if (original.equals(current)) assertArrayEquals(plaintext,decrypt.doFinal(encrypted));
                else assertThrows(AEADBadTagException.class, () -> decrypt.doFinal(encrypted));
            }
        }
    }

    @Test public void oldUnboundStorageFormatRequiresNewAuthentication() throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES"); generator.init(128); SecretKey key=generator.generateKey();
        Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding"); encrypt.init(Cipher.ENCRYPT_MODE,key);
        encrypt.updateAAD("org.privacybubble.app/mobile-session/v1".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=encrypt.doFinal("legacy-session".getBytes(StandardCharsets.UTF_8));
        Cipher decrypt=Cipher.getInstance("AES/GCM/NoPadding"); decrypt.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypt.getIV()));
        decrypt.updateAAD(SessionProfile.associatedData("keyrings-v1"));
        assertThrows(AEADBadTagException.class, () -> decrypt.doFinal(encrypted));
    }
}
