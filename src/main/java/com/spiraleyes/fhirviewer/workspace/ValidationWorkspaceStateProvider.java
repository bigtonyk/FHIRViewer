package com.spiraleyes.fhirviewer.workspace;

import com.spiraleyes.fhirviewer.service.IgPackageManager;
import com.spiraleyes.fhirviewer.model.IgPackageInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Capture/restore boundary for validation state.
 *
 * <p>Serialises IG/package ids and versions, profiles and options. Restore never silently
 * substitutes missing package versions — it warns on missing dependencies.</p>
 */
public class ValidationWorkspaceStateProvider implements WorkspaceStateProvider {

    private final IgPackageManager packageManager;

    /** No-arg constructor: capture/restore are no-ops until wired to a package manager. */
    public ValidationWorkspaceStateProvider() {
        this(null);
    }

    public ValidationWorkspaceStateProvider(IgPackageManager packageManager) {
        this.packageManager = packageManager;
    }

    @Override
    public String providerId() {
        return "validation";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object capture() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (packageManager == null) return out;
        List<IgPackageInfo> packages = packageManager.getLoadedPackages();
        List<Map<String, Object>> entries = packages.stream()
                .map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", p.name());
                    entry.put("version", p.version());
                    entry.put("canonical", p.canonicalUrl());
                    entry.put("fhirVersion", p.fhirVersion());
                    entry.put("description", p.description());
                    return entry;
                })
                .collect(Collectors.toList());
        out.put("packages", entries);
        List<String> active = packageManager.getActivePackages().stream()
                .map(IgPackageInfo::name).collect(Collectors.toList());
        out.put("activePackages", active);
        List<String> inactive = packages.stream()
                .map(IgPackageInfo::name)
                .filter(name -> !active.contains(name))
                .collect(Collectors.toList());
        out.put("inactivePackages", inactive);
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Object snapshot) {
        if (!(snapshot instanceof Map) || packageManager == null) return;
        Map<String, Object> entries = (Map<String, Object>) snapshot;
        // Never silently substitute missing package versions — warn on missing
        Object packages = entries.get("packages");
        if (packages instanceof List<?> list) {
            for (Object p : list) {
                if (p instanceof Map<?, ?> pkg) {
                    Object nameObj = pkg.get("name");
                    Object versionObj = pkg.get("version");
                    String name = nameObj == null ? "" : String.valueOf(nameObj);
                    String version = versionObj == null ? "" : String.valueOf(versionObj);
                    IgPackageInfo installed = packageManager.getPackage(name);
                    if (installed == null || !version.equals(installed.version())) {
                        System.err.println("Workspace restore: validation package '"
                                + name + "#" + version + "' is not installed.");
                    }
                }
            }
        }
    }
}

