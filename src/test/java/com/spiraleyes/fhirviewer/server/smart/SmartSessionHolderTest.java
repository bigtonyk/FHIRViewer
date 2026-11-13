package com.spiraleyes.fhirviewer.server.smart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests the one session shared by the Workbench and the Scripting Console. */
class SmartSessionHolderTest {

    @Test
    void emptyHolderHasNoActiveSession() {
        SmartSessionHolder holder = new SmartSessionHolder();

        assertNull(holder.session());
        assertNull(holder.access());
        assertFalse(holder.api().isSessionActive());
    }

    @Test
    void setPublishesTheSessionToScripts() {
        SmartSessionHolder holder = new SmartSessionHolder();
        SmartAuthorizationSession session = session("tok", "example-1");

        holder.set(session, null);

        assertSame(session, holder.session());
        assertTrue(holder.api().isSessionActive());
        assertEquals("example-1", holder.api().launchPatientId());
        assertEquals(List.of("patient/*.read"), holder.api().grantedScopes());
    }

    @Test
    void clearRemovesTheSessionFromScripts() {
        SmartSessionHolder holder = new SmartSessionHolder();
        holder.set(session("tok", "example-1"), null);

        holder.clear();

        assertNull(holder.session());
        assertNull(holder.access());
        assertFalse(holder.api().isSessionActive());
    }

    @Test
    void logoutOnTheSessionIsVisibleToScripts() {
        SmartSessionHolder holder = new SmartSessionHolder();
        SmartAuthorizationSession session = session("tok", "example-1");
        holder.set(session, null);

        session.logout();

        assertFalse(holder.api().isSessionActive());
    }

    private static SmartAuthorizationSession session(String token, String patient) {
        SmartTokenResponse tokens = new SmartTokenResponse(
                token, "Bearer", 3600L, "patient/*.read", patient, null, null, null);
        return new SmartAuthorizationSession("http://example.com/fhir", tokens, null);
    }
}
