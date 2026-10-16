package com.conductor.integration.connector.apple;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Shared by the Apple connectors: Apple hands out its API keys as PKCS#8 EC {@code .p8} PEM files. */
public final class ApplePrivateKeys {

    private ApplePrivateKeys() {}

    /** Parse a PKCS#8 EC private key out of a .p8 PEM (strip the armor + whitespace, Base64-decode). */
    public static PrivateKey parse(String pem) {
        try {
            String normalized = pem.replace("\\n", "\n");
            String body = normalized
                    .replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse the Apple .p8 private key — must be a PKCS#8 EC PEM ('BEGIN PRIVATE KEY')", e);
        }
    }
}
