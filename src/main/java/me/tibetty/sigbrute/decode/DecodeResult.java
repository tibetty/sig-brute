package me.tibetty.sigbrute.decode;

import java.util.List;
import me.tibetty.sigbrute.decode.strategy.DecodeStrategy;

/**
 * Outcome of a calldata decode: argument tree, non-fatal warnings, and the strategy used.
 */
public record DecodeResult(
    List<DecodedArg> args,
    List<String> warnings,
    List<AlternateStructure> alternateStructures,
    DecodeStrategy strategy
) {

    public DecodeResult {
        args = List.copyOf(args);
        warnings = List.copyOf(warnings);
        alternateStructures = List.copyOf(alternateStructures);
    }

    public DecodeResult(List<DecodedArg> args, List<String> warnings, DecodeStrategy strategy) {
        this(args, warnings, List.of(), strategy);
    }
}
