package me.tibetty.sigbrute.lookup;

import java.util.List;

/**
 * Result of a {@link SignaturePreflight} lookup: verified signatures and any operator-visible
 * diagnostic messages.
 *
 * <p>
 * Keeping I/O out of {@link SignaturePreflight} allows callers to control how and where results
 * are rendered (stdout, logger, test spy, etc.).
 */
public record PreflightResult(List<String> verified, List<String> diagnostics) {

    /** Convenience factory for a successful result with no diagnostics. */
    public static PreflightResult of(List<String> verified) {
        return new PreflightResult(verified, List.of());
    }

    /** Empty result with no matches and no diagnostics. */
    public static PreflightResult empty() {
        return new PreflightResult(List.of(), List.of());
    }

    /** @return {@code true} if at least one verified signature was found */
    public boolean hasMatches() {
        return !verified.isEmpty();
    }
}
