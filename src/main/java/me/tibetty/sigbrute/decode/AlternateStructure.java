package me.tibetty.sigbrute.decode;

import java.util.List;

/**
 * A near-tie heuristic parse with the same calldata but a different structural shape than the
 * winner. Rendered as a commented prototype line in emitted YAML.
 */
public record AlternateStructure(List<DecodedArg> args, String tag) {

    public AlternateStructure {
        args = List.copyOf(args);
    }
}
