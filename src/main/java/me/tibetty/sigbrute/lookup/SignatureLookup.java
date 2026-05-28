package me.tibetty.sigbrute.lookup;

import java.util.List;

/** Resolves a 4-byte selector to known text signatures (4byte.directory / Sourcify). */
public interface SignatureLookup {

    /**
     * Returns known text signatures for the given 4-byte selector, or an empty list if none are
     * found. Implementations may throw {@link LookupException} to signal a transient transport
     * failure (e.g. a non-2xx HTTP response); callers should treat this as an empty result and
     * fall back to brute-force search.
     *
     * @param selector4 exactly 4 bytes representing the ABI function selector
     * @return unmodifiable list of matching text signatures (may be empty)
     * @throws LookupException if a transient transport failure prevents the lookup
     * @throws IllegalArgumentException if {@code selector4} is not exactly 4 bytes
     */
    List<String> lookup(byte[] selector4);
}
