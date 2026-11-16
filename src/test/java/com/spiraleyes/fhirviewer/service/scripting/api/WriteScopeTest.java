package com.spiraleyes.fhirviewer.service.scripting.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class WriteScopeTest {

    @Test
    void allPermitsEveryOperationAndType() {
        WriteScope scope = WriteScope.all();

        for (WriteScope.Operation operation : WriteScope.Operation.values()) {
            assertTrue(scope.allows(operation.apiName(), "Patient"));
            assertTrue(scope.allows(operation.apiName(), "Observation"));
        }
    }

    @Test
    void aLimitedScopeRefusesOtherOperationsAndTypes() {
        WriteScope scope = WriteScope.of(Set.of(WriteScope.Operation.UPDATE),
                Set.of("Patient"));

        assertTrue(scope.allows("update", "Patient"));
        assertTrue(scope.allows("UPDATE", "patient"), "FHIR type matching should ignore case");
        assertFalse(scope.allows("create", "Patient"));
        assertFalse(scope.allows("update", "Observation"));
        assertFalse(scope.allows("update", null));
    }

    @Test
    void anEmptyScopePermitsNothing() {
        assertTrue(WriteScope.none().isEmpty());
        assertFalse(WriteScope.none().allows("update", "Patient"));
    }

    @Test
    void describeNamesTheLimits() {
        WriteScope scope = WriteScope.of(Set.of(WriteScope.Operation.PATCH,
                WriteScope.Operation.UPDATE), Set.of("Patient"));

        assertTrue(scope.describe().contains("patch, update"), scope.describe());
        assertTrue(scope.describe().contains("patient"), scope.describe());
    }
}
