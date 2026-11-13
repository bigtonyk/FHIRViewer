package com.spiraleyes.fhirviewer.server.smart;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Runs one authorization attempt end to end: discovery is already done by
 * the caller, and this flow opens the browser, waits for the loopback
 * callback, exchanges the code, and validates the ID token.
 *
 * <p>Supports both launch modes from {@link SmartLaunchMode}: a standalone
 * launch sends {@code aud} (the FHIR base URL) and no launch token; an EHR
 * launch sends the EHR-supplied {@code launch} token and no {@code aud}.
 *
 * <p>Blocking throughout — the caller runs it on a background thread, never
 * on the JavaFX thread. The loopback server is owned here and is stopped on
 * every path. Nothing is persisted: the session lives in memory and the
 * PKCE verifier never leaves this attempt except in the token POST.</p>
 */
public final class SmartAuthorizationFlow {

    /** How long the flow waits for the browser to return. */
    public static final long CALLBACK_TIMEOUT_MINUTES = 5;

    private final BrowserLauncher browserLauncher;
    private final SmartTokenExchangeService exchangeService;
    private final SmartJwksFetcher jwksFetcher;
    private final SmartDiagnostics diagnostics;

    /** A flow with production collaborators. */
    public SmartAuthorizationFlow() {
        this(new DesktopBrowserLauncher(), new SmartTokenExchangeService(),
                new SmartJwksFetcher(), new SmartDiagnostics());
    }

    public SmartAuthorizationFlow(BrowserLauncher browserLauncher,
            SmartTokenExchangeService exchangeService, SmartJwksFetcher jwksFetcher) {
        this(browserLauncher, exchangeService, jwksFetcher, new SmartDiagnostics());
    }

    /**
     * A flow with chosen collaborators, so a test can supply short deadlines and
     * a diagnostics recorder to capture what the flow does.
     */
    SmartAuthorizationFlow(BrowserLauncher browserLauncher,
            SmartTokenExchangeService exchangeService, SmartJwksFetcher jwksFetcher,
            SmartDiagnostics diagnostics) {
        this.browserLauncher = Objects.requireNonNull(browserLauncher, "browserLauncher");
        this.exchangeService = Objects.requireNonNull(exchangeService, "exchangeService");
        this.jwksFetcher = Objects.requireNonNull(jwksFetcher, "jwksFetcher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Authorizes the launch: opens the browser and returns the session.
     *
     * <p>Standalone launches send {@code aud} (the FHIR base URL) and no
     * launch token; EHR launches send the EHR-supplied {@code launch}
     * token and no {@code aud}.</p>
     *
     * @param config the launch to authorize; either standalone with iss +
     *        client id, or EHR launch with iss + launch parameter
     * @param discovered the discovery document; must be usable
     * @return the in-memory session
     * @throws SmartAuthorizationException when any step fails
     */
    public SmartAuthorizationSession authorize(SmartLaunchConfig config,
            SmartConfiguration discovered) throws SmartAuthorizationException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(discovered, "discovered");
        checkReady(config, discovered);
        diagnostics.record(SmartDiagnosticsEvent.Phase.AUTHORIZATION,
                SmartDiagnosticsEvent.Kind.REQUEST_INITIATED,
                "Authorization request built for " + SmartDiscoveryService.originOf(config.issuer()),
                "mode=" + config.mode() + ", scopes=" + config.scopes());
        SmartAuthState authState = SmartAuthState.generate();
        Pkce pkce = Pkce.generate();
        LoopbackCallbackServer callback = listen(authState.state());
        try {
            callback.start();
            String redirectUri = callback.redirectUri();
            SmartAuthorizationRequest request = requestFor(
                    config, discovered, authState, pkce, redirectUri);
            URI authorizeUrl = request.toUri();
            openBrowser(authorizeUrl);
            String code = awaitCode(callback);
            diagnostics.record(SmartDiagnosticsEvent.Phase.CALLBACK,
                    SmartDiagnosticsEvent.Kind.RECEIVED,
                    "Authorization callback received; code returned from the loopback server",
                    null);
            SmartTokenResponse tokens;
            try {
                tokens = exchangeService.exchange(
                        discovered.tokenEndpoint(), config.clientId(), code,
                        redirectUri, pkce.verifier());
            } catch (SmartAuthorizationException exchangeFailed) {
                diagnostics.record(SmartDiagnosticsEvent.Phase.TOKEN_EXCHANGE,
                        SmartDiagnosticsEvent.Kind.FAILURE,
                        "Token exchange failed: " + exchangeFailed.getMessage(),
                        null);
                throw exchangeFailed;
            }
            diagnostics.record(SmartDiagnosticsEvent.Phase.TOKEN_EXCHANGE,
                    SmartDiagnosticsEvent.Kind.SUCCESS,
                    "Access token received from the token endpoint",
                    "granted scopes: " + (tokens.grantedScope() == null
                            ? "not stated" : tokens.grantedScope())
                            + ", patient context: "
                            + (tokens.patient() == null ? "absent" : "present"));
            String subject = validateIdentity(tokens, config, discovered, authState);
            return new SmartAuthorizationSession(config.issuer(), tokens, subject);
        } finally {
            callback.close();
        }
    }

    private static void checkReady(SmartLaunchConfig config, SmartConfiguration discovered)
            throws SmartAuthorizationException {
        if (!discovered.isUsable()) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST,
                    "This server cannot be launched against: its discovery document "
                            + "is missing a usable authorization or token endpoint.");
        }
        List<String> problems = config.problems();
        if (!problems.isEmpty()) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST, problems.get(0));
        }
        if (!discovered.codeChallengeMethodsSupported().contains(Pkce.METHOD_S256)) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST,
                    "This server does not advertise PKCE S256, which is the only "
                            + "method this application sends.");
        }
    }

    private static LoopbackCallbackServer listen(String state)
            throws SmartAuthorizationException {
        try {
            return new LoopbackCallbackServer(state);
        } catch (IOException e) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.NETWORK,
                    "The local callback listener could not start.", e);
        }
    }

    static SmartAuthorizationRequest requestFor(SmartLaunchConfig config,
            SmartConfiguration discovered, SmartAuthState authState, Pkce pkce,
            String redirectUri) throws SmartAuthorizationException {
        try {
            SmartAuthorizationRequest.Builder builder = SmartAuthorizationRequest
                    .to(discovered.authorizationEndpoint())
                    .clientId(config.clientId())
                    .redirectUri(redirectUri)
                    .scopes(config.scopes())
                    .authState(authState)
                    .pkce(pkce);
            if (config.mode() == SmartLaunchMode.EHR_LAUNCH) {
                // EHR launch: send the EHR-supplied launch token, not aud.
                // The issuer is the resource server and does not need aud.
                if (config.launchParameter() == null || config.launchParameter().isBlank()) {
                    throw new IllegalArgumentException(
                            "An EHR launch needs a launch parameter (the EHR-supplied context token).");
                }
                builder.launchParameter(config.launchParameter());
            } else {
                // Standalone launch: send the FHIR base URL as audience,
                // never a launch token.
                String audience = config.audience() != null ? config.audience() : config.issuer();
                builder.audience(audience);
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.INVALID_REQUEST, e.getMessage(), e);
        }
    }

    private void openBrowser(URI authorizeUrl) throws SmartAuthorizationException {
        BrowserLauncher.LaunchResult launched = browserLauncher.openAuthorizationUrl(authorizeUrl);
        if (launched instanceof BrowserLauncher.LaunchResult.Unavailable unavailable) {
            diagnostics.record(SmartDiagnosticsEvent.Phase.AUTHORIZATION,
                    SmartDiagnosticsEvent.Kind.BROWSER_UNAVAILABLE,
                    "The system browser could not be launched",
                    unavailable.message());
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.BROWSER_UNAVAILABLE,
                    unavailable.message() + " The authorization URL is: " + authorizeUrl);
        }
        diagnostics.record(SmartDiagnosticsEvent.Phase.AUTHORIZATION,
                SmartDiagnosticsEvent.Kind.BROWSER_LAUNCHED,
                "Authorization URL opened in the system browser",
                SmartDiscoveryService.originOf(authorizeUrl.toString()));
    }

    private static String awaitCode(LoopbackCallbackServer callback)
            throws SmartAuthorizationException {
        LoopbackCallbackServer.CallbackOutcome outcome;
        try {
            outcome = callback.await(CALLBACK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmartAuthorizationException(
                    SmartAuthorizationException.Kind.CANCELLED,
                    "The authorization was cancelled.", e);
        }
        if (outcome instanceof LoopbackCallbackServer.CallbackOutcome.Success success) {
            return success.code();
        }
        throw new SmartAuthorizationException(
                SmartAuthorizationException.Kind.INVALID_CALLBACK,
                "The authorization did not complete.");
    }

    private String validateIdentity(SmartTokenResponse tokens, SmartLaunchConfig config,
            SmartConfiguration discovered, SmartAuthState authState)
            throws SmartAuthorizationException {
        if (tokens.idToken() == null) {
            return null;
        }
        List<JWK> keys = jwksFetcher.fetch(discovered.jwksUri());
        JWTClaimsSet claims = SmartIdTokenValidator.validate(tokens.idToken(),
                discovered.issuer() != null ? discovered.issuer() : config.issuer(),
                config.clientId(), authState.nonce(), keys);
        return claims.getSubject();
    }
}

