package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * ID-token validation: signature, issuer, audience, expiry, and nonce.
 * Tokens are signed with throwaway RSA keys generated in-test — no fixture
 * carries a real credential.
 */
class SmartIdTokenValidatorTest {

    private static final String ISSUER = "https://auth.example.com";
    private static final String CLIENT = "public-client";
    private static final String NONCE = "nonce-123";

    @Test
    @DisplayName("A well-formed token validates and yields its claims")
    void validTokenYieldsClaims() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        String token = signed(key, ISSUER, CLIENT, NONCE, minutesFromNow(5));

        JWTClaimsSet claims = SmartIdTokenValidator.validate(
                token, ISSUER, CLIENT, NONCE, List.of((JWK) key.toPublicJWK()));

        assertEquals("patient-user", claims.getSubject());
    }

    @Test
    @DisplayName("An unsigned token is rejected, not decoded as identity")
    void unsignedTokenIsRejected() throws Exception {
        // alg=none compact form built by hand: a PlainJWT would serialize the
        // same way, and this avoids depending on another Nimbus entry point.
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"iss\":\"" + ISSUER + "\",\"sub\":\"s\",\"aud\":\"" + CLIENT
                + "\",\"exp\":" + (System.currentTimeMillis() / 1000 + 300)
                + ",\"nonce\":\"" + NONCE + "\"}");
        String token = header + "." + payload + ".";

        SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                () -> SmartIdTokenValidator.validate(token, ISSUER, CLIENT, NONCE, List.of()));
        assertEquals(SmartAuthorizationException.Kind.ID_TOKEN_INVALID, failure.kind());
    }

    @Test
    @DisplayName("A token signed by an unknown key is rejected")
    void unknownKeyIsRejected() throws Exception {
        RSAKey signer = new RSAKeyGenerator(2048).keyID("k1").generate();
        RSAKey other = new RSAKeyGenerator(2048).keyID("k2").generate();
        String token = signed(signer, ISSUER, CLIENT, NONCE, minutesFromNow(5));

        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                token, ISSUER, CLIENT, NONCE, List.of((JWK) other.toPublicJWK())));
    }

    @Test
    @DisplayName("Wrong issuer, audience, nonce, or expiry is rejected")
    void wrongClaimsAreRejected() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        List<JWK> keys = List.of((JWK) key.toPublicJWK());

        String wrongIssuer = signed(key, "https://other.example.com", CLIENT, NONCE,
                minutesFromNow(5));
        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                wrongIssuer, ISSUER, CLIENT, NONCE, keys));

        String wrongAudience = signed(key, ISSUER, "other-client", NONCE, minutesFromNow(5));
        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                wrongAudience, ISSUER, CLIENT, NONCE, keys));

        String wrongNonce = signed(key, ISSUER, CLIENT, "other-nonce", minutesFromNow(5));
        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                wrongNonce, ISSUER, CLIENT, NONCE, keys));

        String expired = signed(key, ISSUER, CLIENT, NONCE, minutesFromNow(-10));
        SmartAuthorizationException failure = assertThrows(SmartAuthorizationException.class,
                () -> SmartIdTokenValidator.validate(expired, ISSUER, CLIENT, NONCE, keys));
        assertTrue(failure.getMessage().contains("expired"), failure.getMessage());
    }

    @Test
    @DisplayName("No key set means unverifiable, not valid")
    void missingJwksIsUnverifiable() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        String token = signed(key, ISSUER, CLIENT, NONCE, minutesFromNow(5));

        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                token, ISSUER, CLIENT, NONCE, null));
        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                token, ISSUER, CLIENT, NONCE, List.of()));
    }

    @Test
    @DisplayName("Garbage is not a token")
    void garbageIsNotAToken() {
        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                "not-a-jwt", ISSUER, CLIENT, NONCE, List.of()));
        assertThrows(SmartAuthorizationException.class, () -> SmartIdTokenValidator.validate(
                "  ", ISSUER, CLIENT, NONCE, List.of()));
    }

    private static String signed(RSAKey key, String issuer, String audience, String nonce,
            Date expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("patient-user")
                .audience(audience).expirationTime(expiry).issueTime(new Date())
                .claim("nonce", nonce).build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static Date minutesFromNow(int minutes) {
        return new Date(System.currentTimeMillis() + minutes * 60_000L);
    }

    private static String base64Url(String text) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
