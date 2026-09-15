package com.example.fhirviewer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for resource format detection (JSON vs XML). */
class ResourceFormatTest {

    @Test
    @DisplayName("JSON is detected from the leading brace")
    void detectsJsonFromContent() {
        Optional<ResourceFormat> format = ResourceFormat.detect("  \n  {\"resourceType\":\"Patient\"}", null);
        assertEquals(Optional.of(ResourceFormat.JSON), format);
    }

    @Test
    @DisplayName("XML is detected from the leading angle bracket")
    void detectsXmlFromContent() {
        Optional<ResourceFormat> format = ResourceFormat.detect("<?xml version=\"1.0\"?><Patient/>", null);
        assertEquals(Optional.of(ResourceFormat.XML), format);
    }

    @Test
    @DisplayName("A byte order mark does not hide the JSON document")
    void ignoresByteOrderMark() {
        Optional<ResourceFormat> format = ResourceFormat.detect("\uFEFF{\"resourceType\":\"Patient\"}", null);
        assertEquals(Optional.of(ResourceFormat.JSON), format);
    }

    @Test
    @DisplayName("The file extension is used when the content gives no hint")
    void fallsBackToFileExtension() {
        assertEquals(Optional.of(ResourceFormat.XML), ResourceFormat.detect("", "patient.xml"));
        assertEquals(Optional.of(ResourceFormat.JSON), ResourceFormat.detect(null, "PATIENT.JSON"));
        assertEquals(Optional.of(ResourceFormat.XML), ResourceFormat.detect("  ", "folder/patient.xml"));
    }

    @Test
    @DisplayName("Content wins over a misleading file extension")
    void contentWinsOverExtension() {
        assertEquals(Optional.of(ResourceFormat.XML),
                ResourceFormat.detect("<Patient xmlns=\"http://hl7.org/fhir\"/>", "patient.json"));
    }

    @Test
    @DisplayName("Unknown content and unknown extension produce no format")
    void returnsEmptyWhenUndetectable() {
        assertTrue(ResourceFormat.detect("hello world", "notes.txt").isEmpty());
        assertTrue(ResourceFormat.fromFileName("noextension").isEmpty());
    }

    @Test
    @DisplayName("The other format can be requested")
    void providesTheOtherFormat() {
        assertEquals(ResourceFormat.XML, ResourceFormat.JSON.other());
        assertEquals(ResourceFormat.JSON, ResourceFormat.XML.other());
    }

    @Test
    @DisplayName("Extensions and display names are exposed for the UI")
    void exposesDisplayInformation() {
        assertEquals("json", ResourceFormat.JSON.getExtension());
        assertEquals("xml", ResourceFormat.XML.getExtension());
        assertFalse(ResourceFormat.JSON.getDisplayName().isBlank());
    }
}