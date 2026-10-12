package ca.hex42labs.sports.persistence;

/** Unchecked wrapper for database failures; nothing upstream of persistence can fix them. */
public class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
