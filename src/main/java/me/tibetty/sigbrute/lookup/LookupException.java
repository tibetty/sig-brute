package me.tibetty.sigbrute.lookup;

/**
 * Thrown by {@link SignatureLookup} implementations when a transient transport failure occurs
 * (e.g. a non-2xx HTTP response). Callers may inspect {@link #statusCode()} to distinguish
 * rate-limit responses (429) from server errors (5xx), but should generally treat any
 * {@code LookupException} as a signal to fall back to brute-force search.
 */
public final class LookupException extends RuntimeException {

    private final int statusCode;

    public LookupException(int statusCode) {
        super("HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    /** Returns the HTTP status code that triggered this exception. */
    public int statusCode() {
        return statusCode;
    }
}
