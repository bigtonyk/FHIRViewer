package com.spiraleyes.fhirviewer.server.smart;

import java.text.ParseException;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Validates an OIDC identity token against the discovery metadata.
 *
 * <p>Decoding a JWT is not validation: this class checks the signature
 * against the server's JWKS, then the issuer, audience, expiry, and nonce.
 * Nimbus JOSE+JWT performs the cryptographic verification — no protocol
 * primitive is implemented here from scratch.</p>
 */
public final class SmartIdTokenValidator {

    /** Clock tolerance for expiry checks. */
    static final long CLOCK_SKEW_SECONDS = 60;

    private SmartIdTokenValidator() {
    }

    /**
     * Validates an identity token.
     *
     * @param idToken the compact JWT; never blank
     * @param expectedIssuer the issuer from discovery; never blank
     * @param expectedAudience the client id; never blank
     * @param expectedNonce the nonce from the authorize request; never blank
     * @param jwks the server's key set, or {@code null} when unpublished
     * @return the validated claims
     * @throws SmartAuthorizationException when validation fails
     */
    public static JWTClaimsSet validate(String idToken, String expectedIssuer,
            String expectedAudience, String expectedNonce, List<JWK> jwks)
            throws SmartAuthorizationException {
        require(idToken, "An identity token is required.");
        require(expectedIssuer, "An expected issuer is required.");
        require(expectedAudience, "An expected audience is required.");
        require(expectedNonce, "An expected nonce is required.");
        SignedJWT jwt = parseSigned(idToken.strip());
        verifySignature(jwt, jwks);
        JWTClaimsSet claims = claimsOf(jwt);
        checkIssuer(claims, expectedIssuer.strip());
        checkAudience(claims, expectedAudience.strip());
        checkExpiry(claims);
        checkNonce(claims, expectedNonce);
        return claims;
    }

    private static SignedJWT parseSigned(String idToken) throws SmartAuthorizationException {
        JWT parsed;
        try {
            parsed = JWTParser.parse(idToken);
        } catch (ParseException e) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token is not a well-formed JWT.", e);
        }
        if (parsed instanceof PlainJWT) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token is unsigned; its claims cannot be trusted.");
        }
        if (parsed instanceof SignedJWT signed) {
            return signed;
        }
        throw new SmartAuthorizationException(
                SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                "The identity token is not a signed JWT.");
    }

    private static JWTClaimsSet claimsOf(SignedJWT jwt) throws SmartAuthorizationException {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token claims could not be read.", e);
        }
    }

    private static void verifySignature(SignedJWT jwt, List<JWK> jwks)
            throws SmartAuthorizationException {
        if (jwks == null || jwks.isEmpty()) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token cannot be verified: the server publishes no key set.");
        }
        String keyId = jwt.getHeader().getKeyID();
        Exception lastProblem = null;
        for (JWK key : jwks) {
            if (keyId != null && key.getKeyID() != null && !keyId.equals(key.getKeyID())) {
                continue;
            }
            JWSVerifier verifier;
            try {
                verifier = verifierFor(key);
            } catch (JOSEException e) {
                lastProblem = e;
                continue;
            }
            if (verifier == null) {
                continue;
            }
            try {
                if (jwt.verify(verifier)) {
                    return;
                }
            } catch (JOSEException e) {
                lastProblem = e;
            }
        }
        throw new SmartAuthorizationException(
                SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                "The identity token signature does not match any published key.",
                lastProblem);
    }

    /** A verifier for the key types servers publish; null when unknown. */
    static JWSVerifier verifierFor(JWK key) throws JOSEException {
        if (key instanceof RSAKey rsa) {
            return new RSASSAVerifier(rsa);
        }
        if (key instanceof ECKey ec) {
            return new ECDSAVerifier(ec);
        }
        if (key instanceof OctetSequenceKey oct) {
            return new MACVerifier(oct);
        }
        return null;
    }

    private static void checkIssuer(JWTClaimsSet claims, String expected)
            throws SmartAuthorizationException {
        if (!expected.equals(claims.getIssuer())) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token was issued by an unexpected issuer.");
        }
    }

    private static void checkAudience(JWTClaimsSet claims, String expected)
            throws SmartAuthorizationException {
        List<String> audience = claims.getAudience();
        if (audience == null || audience.stream().noneMatch(expected::equals)) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token was not issued for this client.");
        }
    }

    private static void checkExpiry(JWTClaimsSet claims)
            throws SmartAuthorizationException {
        Date expiry = claims.getExpirationTime();
        if (expiry == null) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token carries no expiry and cannot be trusted.");
        }
        if (expiry.getTime() + CLOCK_SKEW_SECONDS * 1000 < System.currentTimeMillis()) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token has expired.");
        }
    }

    private static void checkNonce(JWTClaimsSet claims, String expected)
            throws SmartAuthorizationException {
        String nonce;
        try {
            nonce = claims.getStringClaim("nonce");
        } catch (ParseException e) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token nonce could not be read.", e);
        }
        if (!expected.equals(nonce)) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.ID_TOKEN_INVALID,
                    "The identity token does not match this authorization attempt.");
        }
    }

    private static void require(String value, String message)
            throws SmartAuthorizationException {
        if (value == null || value.isBlank()) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST, message);
        }
    }
}

