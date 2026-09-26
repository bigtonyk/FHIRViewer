package com.example.fhirviewer.server;

/**
 * Signals that a secret could not be encrypted or decrypted.
 *
 * <p>Kept separate from the general security failures so the UI can tell "you typed the
 * wrong passphrase" apart from an I/O problem without inspecting message text.</p>
 */
public class SecretBoxException extends Exception {

    private static final long serialVersionUID = 1L;

    public SecretBoxException(String message) {
        super(message);
    }

    public SecretBoxException(String message, Throwable cause) {
        super(message, cause);
    }
}
