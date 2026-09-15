package com.example.fhirviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.fhirviewer.model.ResourceNode;

/**
 * Test helpers for navigating and describing a generated resource tree.
 */
public final class TreeAssert {

    private TreeAssert() {
        // static helpers
    }

    /** Finds the first node with the given path, for example <code>Patient.name[0].given[1]</code>. */
    public static Optional<ResourceNode> find(ResourceNode root, String path) {
        if (root == null) {
            return Optional.empty();
        }
        if (path.equals(root.getPath())) {
            return Optional.of(root);
        }
        for (ResourceNode child : root.getChildren()) {
            Optional<ResourceNode> match = find(child, path);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /** Returns a node with the given path, failing with a readable message when absent. */
    public static ResourceNode require(ResourceNode root, String path) {
        return find(root, path).orElseThrow(() -> new AssertionError(
                "No node found with path <" + path + ">. Tree contains:\n" + String.join("\n", paths(root))));
    }

    /** All paths in the tree, depth first. */
    public static List<String> paths(ResourceNode root) {
        List<String> result = new ArrayList<>();
        collectPaths(root, result);
        return result;
    }

    /** All display texts in the tree, depth first. */
    public static List<String> displayTexts(ResourceNode root) {
        List<String> result = new ArrayList<>();
        collectDisplayTexts(root, result);
        return result;
    }

    /** The number of nodes in the tree, including the root. */
    public static int countNodes(ResourceNode root) {
        if (root == null) {
            return 0;
        }
        int count = 1;
        for (ResourceNode child : root.getChildren()) {
            count += countNodes(child);
        }
        return count;
    }

    private static void collectPaths(ResourceNode node, List<String> result) {
        result.add(node.getPath());
        for (ResourceNode child : node.getChildren()) {
            collectPaths(child, result);
        }
    }

    private static void collectDisplayTexts(ResourceNode node, List<String> result) {
        result.add(node.getDisplayText());
        for (ResourceNode child : node.getChildren()) {
            collectDisplayTexts(child, result);
        }
    }
}