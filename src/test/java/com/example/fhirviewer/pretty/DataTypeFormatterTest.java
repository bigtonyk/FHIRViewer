package com.example.fhirviewer.pretty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import com.example.fhirviewer.fhir.FhirContextFactory;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the human friendly renderings of the FHIR datatypes used by the
 * Pretty View. The values are built directly from the HAPI model so each
 * summary can be checked in isolation.
 */
class DataTypeFormatterTest {

    private static DataTypeFormatter formatter;

    @BeforeAll
    static void createFormatter() {
        formatter = new DataTypeFormatter(FhirContextFactory.r4ModelAdapter());
    }

    private static String summarize(IBase value) {
        return formatter.summarize(value);
    }

    @Test
    @DisplayName("Quantities render as value and unit")
    void summarizesQuantity() {
        Quantity quantity = new Quantity()
                .setValue(new BigDecimal("72"))
                .setUnit("beats/minute");

        assertEquals("72 beats/minute", summarize(quantity));
    }

    @Test
    @DisplayName("Quantities with a coded system append the system and code")
    void summarizesQuantityWithSystem() {
        Quantity quantity = new Quantity()
                .setValue(new BigDecimal("72"))
                .setUnit("beats/minute")
                .setSystem("http://unitsofmeasure.org")
                .setCode("/min");

        assertEquals("72 beats/minute (UCUM /min)", summarize(quantity));
    }

    @Test
    @DisplayName("Codings render as display with a friendly system name and code")
    void summarizesCoding() {
        Coding coding = new Coding("http://loinc.org", "8867-4", "Heart rate");

        assertEquals("Heart rate (LOINC 8867-4)", summarize(coding));
    }

    @Test
    @DisplayName("Codings without a display show the system and code")
    void summarizesCodingWithoutDisplay() {
        Coding coding = new Coding("http://loinc.org", "8867-4", null);

        assertEquals("(LOINC 8867-4)", summarize(coding));
    }

    @Test
    @DisplayName("CodeableConcept prefers its text over its codings")
    void summarizesCodeableConceptWithText() {
        CodeableConcept concept = new CodeableConcept();
        concept.setText("Heart rate");
        concept.addCoding(new Coding("http://loinc.org", "8867-4", "Heart rate"));

        assertEquals("Heart rate", summarize(concept));
    }

    @Test
    @DisplayName("CodeableConcept without text falls back to its first coding")
    void summarizesCodeableConceptWithoutText() {
        CodeableConcept concept = new CodeableConcept();
        concept.addCoding(new Coding("http://snomed.info/sct", "266919005", "Never smoked"));

        assertEquals("Never smoked (SNOMED CT 266919005)", summarize(concept));
    }

    @Test
    @DisplayName("Human names render as the composed name")
    void summarizesHumanName() {
        HumanName name = new HumanName()
                .setFamily("Smith")
                .addGiven("John")
                .addGiven("Jacob");

        assertEquals("John Jacob Smith", summarize(name));
    }

    @Test
    @DisplayName("Human names with text render the text as written")
    void summarizesHumanNameWithText() {
        HumanName name = new HumanName().setText("Dr. John Smith Jr.");
        name.setFamily("Smith");

        assertEquals("Dr. John Smith Jr.", summarize(name));
    }

    @Test
    @DisplayName("Addresses render as a single postal address line")
    void summarizesAddress() {
        Address address = new Address()
                .addLine("123 Main St")
                .setCity("Springfield")
                .setState("IL")
                .setPostalCode("62701");

        assertEquals("123 Main St, Springfield, IL 62701", summarize(address));
    }

    @Test
    @DisplayName("Contact points render as value with system and use")
    void summarizesContactPoint() {
        ContactPoint contact = new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.PHONE)
                .setValue("+1-555-0100")
                .setUse(ContactPoint.ContactPointUse.WORK);

        assertEquals("+1-555-0100 (phone work)", summarize(contact));
    }

    @Test
    @DisplayName("References render as display with the target")
    void summarizesReference() {
        Reference reference = new Reference("Patient/example-1").setDisplay("John Smith");

        assertEquals("John Smith (Patient/example-1)", summarize(reference));
    }

    @Test
    @DisplayName("References without a display show only the target")
    void summarizesReferenceWithoutDisplay() {
        Reference reference = new Reference("Patient/example-1");

        assertEquals("Patient/example-1", summarize(reference));
    }

    @Test
    @DisplayName("Contained references are labeled")
    void summarizesContainedReference() {
        Reference reference = new Reference("#contained-observation");

        assertEquals("(contained resource)", summarize(reference));
    }

    @Test
    @DisplayName("Periods render as a start to end range")
    void summarizesPeriod() {
        Period period = new Period()
                .setStartElement(new DateTimeType("2024-05-01T10:15:00Z"))
                .setEndElement(new DateTimeType("2024-05-01T11:00:00Z"));

        assertEquals("2024-05-01 10:15:00 UTC → 2024-05-01 11:00:00 UTC", summarize(period));
    }

    @Test
    @DisplayName("Open ended periods are marked as ongoing")
    void summarizesOpenEndedPeriod() {
        Period period = new Period().setStartElement(new DateTimeType("2024-05-01"));

        assertEquals("2024-05-01 → (ongoing)", summarize(period));
    }

    @Test
    @DisplayName("Date times render with a readable UTC time")
    void prettifiesDateTime() {
        assertEquals("2024-05-01 10:15:00 UTC",
                formatter.textOf(new DateTimeType("2024-05-01T10:15:00Z")));
        assertEquals("2024-05-01",
                formatter.textOf(new DateTimeType("2024-05-01")));
    }

    @Test
    @DisplayName("Extension names are derived from the canonical URL")
    void derivesExtensionNames() {
        assertEquals("Patient Race", formatter.extensionName(
                new Extension("http://example.org/fhir/StructureDefinition/patient-race")));
        assertEquals("My Ext", formatter.extensionName(new Extension("#my-ext")));
        assertEquals("Extension", formatter.extensionName(new Extension()));
    }

    @Test
    @DisplayName("Extensions summarize their value")
    void summarizesExtensionValue() {
        Extension extension = new Extension("http://example.org/fhir/StructureDefinition/patient-race")
                .setValue(new StringType("white"));

        assertEquals("Patient Race = white", summarize(extension));
    }

    @Test
    @DisplayName("Only datatypes with a friendly rendering are summarizable")
    void knowsSummarizableDatatypes() {
        assertTrue(formatter.isSummarizable(new Quantity()));
        assertTrue(formatter.isSummarizable(new HumanName()));
        assertTrue(formatter.isSummarizable(new Reference()));
        assertFalse(formatter.isSummarizable(new StringType()));
        assertFalse(formatter.isSummarizable(new org.hl7.fhir.r4.model.Meta()));
    }
}