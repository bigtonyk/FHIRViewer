// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small built-in catalogue of well-known FHIR packages so keyword search
 * (Update 3, e.g. "us core") works even though the npm registry has no
 * keyword-search endpoint.
 */
public final class KnownPackages {

    public record Entry(String id, String title, String description, String fhirVersion) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("hl7.fhir.r4.core", "FHIR R4 Core", "Base FHIR R4 specification package", "R4"),
            new Entry("hl7.fhir.r4b.core", "FHIR R4B Core", "Base FHIR R4B specification package", "R4B"),
            new Entry("hl7.fhir.r5.core", "FHIR R5 Core", "Base FHIR R5 specification package", "R5"),
            new Entry("hl7.fhir.us.core", "US Core", "US Core Implementation Guide", "R4"),
            new Entry("hl7.fhir.uv.smart-app-launch", "SMART App Launch",
                    "SMART App Launch framework for FHIR apps", "R4"),
            new Entry("hl7.fhir.uv.extensions", "FHIR Extensions Pack",
                    "HL7 FHIR extensions pack", "R4"),
            new Entry("hl7.terminology", "HL7 Terminology", "HL7 terminology code systems and value sets", "R4"),
            new Entry("hl7.fhir.us.davinci-pdex", "Da Vinci PDEX",
                    "Da Vinci Payer Data Exchange", "R4"),
            new Entry("hl7.fhir.us.davinci-pas", "Da Vinci PAS",
                    "Da Vinci Prior Authorization Support", "R4"),
            new Entry("hl7.fhir.us.davinci-dtr", "Da Vinci DTR",
                    "Da Vinci Documentation Templates and Rules", "R4"),
            new Entry("hl7.fhir.us.davinci-crd", "Da Vinci CRD",
                    "Da Vinci Coverage Requirements Discovery", "R4"),
            new Entry("hl7.fhir.us.davinci-cdex", "Da Vinci CDex",
                    "Da Vinci Clinical Data Exchange", "R4"),
            new Entry("hl7.fhir.us.davinci-hrex", "Da Vinci HRex",
                    "Da Vinci Health Record Exchange", "R4"),
            new Entry("hl7.fhir.us.carin-bb", "CARIN Blue Button",
                    "CARIN Consumer Directed Payer Data Exchange", "R4"),
            new Entry("hl7.fhir.us.ecr", "eCR Now", "Electronic Case Reporting", "R4"));

    private KnownPackages() {
    }

    /**
     * Finds catalogue entries matching every token of the query against id,
     * title and description. Exact package-id queries always match first.
     */
    public static List<Entry> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            byId.put(e.id().toLowerCase(Locale.ROOT), e);
        }
        List<Entry> results = new ArrayList<>();
        Entry exact = byId.get(q);
        if (exact != null) {
            results.add(exact);
        }
        String[] tokens = q.split("[^a-z0-9]+");
        for (Entry e : ENTRIES) {
            if (results.contains(e)) {
                continue;
            }
            String haystack = (e.id() + " " + e.title() + " " + e.description())
                    .toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String token : tokens) {
                if (!token.isEmpty() && !haystack.contains(token)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                results.add(e);
            }
        }
        return results;
    }

    /** True when the query is exactly a known package id. */
    public static boolean isKnownId(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return ENTRIES.stream().anyMatch(e -> e.id().equalsIgnoreCase(q));
    }
}
