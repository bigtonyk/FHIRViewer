package com.spiraleyes.fhirviewer.ui;

import org.hl7.fhir.instance.model.api.IBaseResource;

import com.spiraleyes.fhirviewer.model.LoadedResource;
import com.spiraleyes.fhirviewer.model.ValidationReport;
import com.spiraleyes.fhirviewer.server.ServerOrigin;

/**
 * What the extracted UI classes need from the window's document state.
 *
 * <p>R4 §7 named this seam: the server actions and the server screens both touch the same
 * few facts about the displayed resource and the same three operations — show a resource,
 * ask before replacing it, hand out its JSON — so they take this port instead of a reference
 * to the window. {@code MainWindow} implements it once, in its constructor, over the fields
 * it already owns; nothing else changes hands.</p>
 *
 * <p>It is a port, not a presenter: there is no event stream, no state machine and no second
 * copy of the document. Every method reads or writes the window's existing state, so the
 * dirty flag, the undo history and the origin can only ever live in one place.</p>
 */
interface DocumentPort {

    /** The resource the editor holds, or {@code null} when nothing is open. */
    IBaseResource resource();

    /** The loaded document the editor holds, or {@code null} when nothing is open. */
    LoadedResource loaded();

    /** The name the window shows for the displayed resource. */
    String label();

    /** Where that resource came from, or {@code null} when it did not come from a server. */
    ServerOrigin origin();

    /** Shows a resource, recording where it came from; a {@code null} origin means nowhere. */
    void show(LoadedResource resource, ServerOrigin origin);

    /** Asks before unsaved work is discarded; {@code false} stops the action. */
    boolean confirmDiscard(String action);

    /** The displayed resource as JSON, or {@code null} when nothing is open. */
    String currentJson();

    /** Clears the editor. */
    void close();

    /** Shows the issues a write or a validation run reported. */
    void showValidation(ValidationReport report);

    /** Turns the window's busy cursor on or off. */
    void busy(boolean busy);
}
