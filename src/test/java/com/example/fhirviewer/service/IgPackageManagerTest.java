package com.example.fhirviewer.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.fhirviewer.model.IgPackageInfo;

/**
 * Tests IG package management functionality.
 */
class IgPackageManagerTest {

    private static IgPackageManager manager;

    @BeforeAll
    static void createManager() {
        manager = new IgPackageManager();
    }

    @Test
    @DisplayName("New package manager has no loaded packages")
    void newManagerHasNoPackages() {
        assertTrue(manager.getLoadedPackages().isEmpty());
        assertTrue(manager.getLoadedPackageCount() == 0);
        assertTrue(!manager.hasLoadedPackages());
    }

    @Test
    @DisplayName("Package manager can be created")
    void managerCanBeCreated() {
        assertNotNull(manager);
    }
}