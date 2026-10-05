package com.example.fhirviewer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The console's authentication factory, and the rule that a half-filled credential form
 * fails loudly rather than quietly sending an anonymous request.
 *
 * <p>The last of those is the property worth having a test for. A console that fell back to
 * anonymous when the user left the token box empty would answer {@code 401}, and the user
 * would go looking for a permissions problem that they do not have.
 */
class AdhocAuthenticationTest {

    @Test
    @DisplayName("Anonymous is the default, and sends nothing")
    void anonymousByDefault() {
        assertSame(AnonymousServerAuthentication.INSTANCE,
                AdhocAuthentication.of(ServerAuthKind.ANONYMOUS, "", "", ""));
        assertSame(AnonymousServerAuthentication.INSTANCE,
                AdhocAuthentication.of(null, "", "", ""));
    }

    @Test
    @DisplayName("Basic builds a user name and password")
    void basicFromUserAndPassword() {
        ServerAuthentication authentication =
                AdhocAuthentication.of(ServerAuthKind.BASIC, "alice", "s3cret", "");

        BasicServerAuthentication basic =
                assertInstanceOf(BasicServerAuthentication.class, authentication);
        assertEquals("alice", basic.userName());
        assertFalse(basic.isAnonymous());
    }

    @Test
    @DisplayName("Bearer builds a token")
    void bearerFromToken() {
        ServerAuthentication authentication =
                AdhocAuthentication.of(ServerAuthKind.BEARER, "", "", "abc.def.ghi");

        assertEquals("bearer", authentication.type());
        assertFalse(authentication.isAnonymous());
    }

    @Test
    @DisplayName("A missing user name is refused, naming the field")
    void basicWithoutUserNameIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AdhocAuthentication.of(ServerAuthKind.BASIC, "  ", "pw", ""));

        assertTrue(failure.getMessage().contains("user name"), failure.getMessage());
    }

    @Test
    @DisplayName("A missing token is refused, naming the field")
    void bearerWithoutTokenIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AdhocAuthentication.of(ServerAuthKind.BEARER, "", "", ""));

        assertTrue(failure.getMessage().contains("access token"), failure.getMessage());
    }

    @Test
    @DisplayName("A blank password is allowed: an empty password is a real thing")
    void basicWithEmptyPasswordIsAllowed() {
        assertInstanceOf(BasicServerAuthentication.class,
                AdhocAuthentication.of(ServerAuthKind.BASIC, "alice", "", ""));
    }

    @Test
    @DisplayName("The missing-field question can be asked without catching anything")
    void missingFieldCanBeAsked() {
        assertFalse(AdhocAuthentication.isMissingField(ServerAuthKind.ANONYMOUS, "", ""));
        assertTrue(AdhocAuthentication.isMissingField(ServerAuthKind.BASIC, "", ""));
        assertFalse(AdhocAuthentication.isMissingField(ServerAuthKind.BASIC, "alice", ""));
        assertTrue(AdhocAuthentication.isMissingField(ServerAuthKind.BEARER, "", ""));

        assertEquals("user name", AdhocAuthentication.missingFieldLabel(ServerAuthKind.BASIC));
        assertEquals("access token", AdhocAuthentication.missingFieldLabel(ServerAuthKind.BEARER));
    }

    @Test
    @DisplayName("The factory itself reveals nothing and cannot be instantiated")
    void theFactoryIsNotUsableAsAValue() throws Exception {
        java.lang.reflect.Constructor<AdhocAuthentication> constructor =
                AdhocAuthentication.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                "a class of static methods should not offer a public constructor");

        ServerAuthentication basic =
                AdhocAuthentication.of(ServerAuthKind.BASIC, "alice", "s3cret", "");
        assertFalse(basic.toString().contains("s3cret"), basic.toString());
    }
}