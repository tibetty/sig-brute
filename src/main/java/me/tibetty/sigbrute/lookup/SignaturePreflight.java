package me.tibetty.sigbrute.lookup;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.util.HexUtil;
import me.tibetty.sigbrute.util.Keccak256Util;

/** Optional pre-search lookup against public signature databases. */
public final class SignaturePreflight {

    private SignaturePreflight() {
    }

    /**
     * Queries {@link SignatureLookup} and prints verified hits. Returns signatures whose Keccak
     * selector matches {@code selector4}.
     */
    public static List<String> run(byte[] selector4, SignatureLookup lookup, PrintStream out,
        PrintStream err, boolean skipLookup) {
        if (skipLookup) {
            return List.of();
        }

        List<String> raw;
        try {
            raw = lookup.lookup(selector4);
        } catch (RuntimeException e) {
            err.println("lookup: " + e.getMessage());
            return List.of();
        }

        if (raw.isEmpty()) {
            return List.of();
        }

        var verified = new ArrayList<String>();
        for (var sig : raw) {
            if (Keccak256Util.selectorMatches(sig, selector4)) {
                verified.add(sig);
            }
        }

        if (verified.isEmpty()) {
            err.println("lookup: API returned entries but none verified for selector 0x"
                + HexUtil.toHex(selector4));
            return List.of();
        }

        out.println("Known signatures (4byte / Sourcify):");
        verified.forEach(s -> out.println("  " + s));
        out.println();
        return verified;
    }
}
