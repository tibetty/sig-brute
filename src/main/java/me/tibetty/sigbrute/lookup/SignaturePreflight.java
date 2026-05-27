package me.tibetty.sigbrute.lookup;

import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import me.tibetty.sigbrute.util.Keccak256Util;

/** Optional pre-search lookup against public signature databases. */
public final class SignaturePreflight {

    private SignaturePreflight() {
    }

    /**
     * Queries {@link SignatureLookup} and returns a {@link PreflightResult}. The
     * {@code verified} list contains signatures whose Keccak-256 selector matches
     * {@code selector4}; {@code diagnostics} holds any operator-visible warning messages.
     * Printing is the caller's responsibility.
     */
    public static PreflightResult run(byte[] selector4, SignatureLookup lookup,
        boolean skipLookup) {
        if (skipLookup) {
            return PreflightResult.empty();
        }

        List<String> raw;
        try {
            raw = lookup.lookup(selector4);
        } catch (RuntimeException e) {
            // Use a fixed message; e.getMessage() may contain the full request URL, which
            // could end up persisted in CI logs or shell history.
            return new PreflightResult(List.of(),
                List.of("lookup: signature database unavailable — skipping preflight"));
        }

        if (raw.isEmpty()) {
            return PreflightResult.empty();
        }

        var verified = new ArrayList<String>();
        for (var sig : raw) {
            if (Keccak256Util.selectorMatches(sig, selector4)) {
                verified.add(sig);
            }
        }

        if (verified.isEmpty()) {
            return new PreflightResult(List.of(),
                List.of("lookup: API returned entries but none verified for selector 0x"
                    + HexUtil.toHex(selector4)));
        }

        return PreflightResult.of(List.copyOf(verified));
    }
}
