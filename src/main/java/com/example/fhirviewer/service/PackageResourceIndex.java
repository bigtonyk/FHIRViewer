// Copyright (c) 2026 FHIRViewer contributors. SPDX-License-Identifier: GPL-3.0-only.
package com.example.fhirviewer.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.MetadataResource;

import com.example.fhirviewer.model.ValidationProfile;

/**
 * Canonical URL index over the conformance resources of installed FHIR
 * packages (Update 8). Lets the application resolve
 * {@code http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient} to
 * the StructureDefinition of the installed US Core package, know which package
 * and version provided it, and list the profiles applicable to a resource type.
 */
public final class PackageResourceIndex {

    /** A conformance resource together with the package that provided it. */
    public record IndexedResource(
            String canonical,
            String resourceType,
            String displayName,
            String packageId,
            String packageVersion,
            IBaseResource resource) {

        /** The profile description used in validation results. */
        public ValidationProfile toValidationProfile() {
            return new ValidationProfile(canonical, displayName, packageId, packageVersion);
        }

        /** For example <code>US Core Patient 9.0.0</code>. */
        public String label() {
            String name = displayName.isEmpty() ? canonical : displayName;
            return packageVersion.isEmpty() ? name : name + " " + packageVersion;
        }
    }

    private final Map<String, IndexedResource> byCanonical = new LinkedHashMap<>();
    private final Map<String, List<IndexedResource>> profilesByResourceType = new TreeMap<>();

    /** Indexes every conformance resource of one package. */
    public void addPackage(String packageId, String packageVersion,
            Collection<IBaseResource> resources) {
        if (resources == null) {
            return;
        }
        for (IBaseResource resource : resources) {
            if (!(resource instanceof MetadataResource canonicalResource)) {
                continue;
            }
            String canonical = canonicalResource.hasUrl() ? canonicalResource.getUrl() : null;
            if (canonical == null || canonical.isBlank()) {
                continue;
            }
            String type = resource.fhirType();
            IndexedResource indexed = new IndexedResource(
                    canonical, type, displayNameOf(canonicalResource, canonical),
                    packageId, packageVersion, resource);
            byCanonical.putIfAbsent(canonical, indexed);
            if ("StructureDefinition".equals(type)) {
                profilesByResourceType
                        .computeIfAbsent(baseTypeOf(resource), key -> new ArrayList<>())
                        .add(indexed);
            }
        }
    }

    /** Removes every indexed resource. */
    public void clear() {
        byCanonical.clear();
        profilesByResourceType.clear();
    }

    /** The resource with the given canonical URL, or null when not indexed. */
    public IndexedResource lookup(String canonical) {
        return canonical == null ? null : byCanonical.get(canonical);
    }

    /**
     * Describes the profile with the given canonical URL for use in validation
     * results, or null when no installed package provides it.
     */
    public ValidationProfile describe(String canonical) {
        IndexedResource indexed = lookup(canonical);
        return indexed == null ? null : indexed.toValidationProfile();
    }

    /** StructureDefinitions that constrain the given resource type, title order. */
    public List<IndexedResource> profilesFor(String resourceType) {
        if (resourceType == null) {
            return List.of();
        }
        List<IndexedResource> found = profilesByResourceType.get(resourceType);
        return found == null ? List.of() : List.copyOf(found);
    }

    /** Total number of indexed conformance resources. */
    public int size() {
        return byCanonical.size();
    }

    /** True when nothing has been indexed. */
    public boolean isEmpty() {
        return byCanonical.isEmpty();
    }

    /**
     * The resource type a StructureDefinition constrains: its {@code type}
     * field when present, otherwise its {@code baseDefinition} target.
     */
    private static String baseTypeOf(IBaseResource resource) {
        if (resource instanceof org.hl7.fhir.r4.model.StructureDefinition sd) {
            if (sd.hasType()) {
                return sd.getType();
            }
            if (sd.hasBaseDefinition()) {
                String base = sd.getBaseDefinition();
                int slash = base.lastIndexOf('/');
                return slash < 0 ? base : base.substring(slash + 1);
            }
        }
        return resource.fhirType();
    }

    /** A title, name or last canonical path segment for the resource. */
    private static String displayNameOf(MetadataResource resource, String canonical) {
        if (resource.hasTitle()) {
            return resource.getTitle();
        }
        if (resource.hasName()) {
            return resource.getName();
        }
        int slash = canonical.lastIndexOf('/');
        return slash < 0 || slash == canonical.length() - 1
                ? canonical
                : canonical.substring(slash + 1);
    }
}
