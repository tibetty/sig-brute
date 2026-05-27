package me.tibetty.sigbrute.decode.strategy;

import java.util.List;
import java.util.function.Function;
import me.tibetty.sigbrute.decode.AlternateStructure;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.DecodeResult;
import me.tibetty.sigbrute.decode.SignatureStructure;

/**
 * Per-strategy decode hooks: tuple-body recursion, dynamic tails, and static-slot inference.
 * Passed explicitly through skeleton and body decoders (no thread-local state).
 *
 * <p>
 * Although {@code DecodeContext} is a {@code record}, the {@code warnings} and
 * {@code alternateStructures} fields are intentionally mutable {@link java.util.ArrayList}
 * instances. This is a deliberate builder-accumulator pattern: one context accumulates
 * diagnostics during a single decode pass, and {@link #toResult} snapshots them via
 * {@link List#copyOf} before returning an immutable {@link DecodeResult}. The context itself
 * is never shared across calls. Obtain a fresh instance per decode via
 * {@link DecodeStrategy#newContext}.
 *
 * <p>
 * Factory methods (greedy, heuristicSearch, wide variants) live on {@link DecodeStrategy}, not
 * here, to avoid a circular {@code strategy} ↔ {@code strategy.body} package dependency.
 */
public record DecodeContext(
    BodyDecoder bodyDecoder,
    DynamicDecoder dynamicDecoder,
    Function<byte[], List<String>> inferStatic,
    List<String> warnings,
    List<AlternateStructure> alternateStructures
) {

    @FunctionalInterface
    public interface BodyDecoder {
        List<DecodedArg> decode(DecodeContext ctx, byte[] body);
    }

    @FunctionalInterface
    public interface DynamicDecoder {
        DecodedArg decode(DecodeContext ctx, byte[] body);
    }

    public List<DecodedArg> decodeBody(byte[] body) {
        return bodyDecoder.decode(this, body);
    }

    public DecodedArg decodeDynamic(byte[] body) {
        return dynamicDecoder.decode(this, body);
    }

    public List<String> inferStatic(byte[] word) {
        return inferStatic.apply(word);
    }

    public void warn(String message) {
        if (message != null && !message.isBlank()) {
            warnings.add(message);
        }
    }

    public void recordAlternate(List<DecodedArg> args, String tag) {
        if (args == null || args.isEmpty()) {
            return;
        }
        var shape = SignatureStructure.fromDecodedArgs(args);
        for (var existing : alternateStructures) {
            if (shape.structureEquals(SignatureStructure.fromDecodedArgs(existing.args()))) {
                return;
            }
        }
        alternateStructures.add(new AlternateStructure(args, tag));
    }

    public DecodeResult toResult(List<DecodedArg> args, DecodeStrategy strategy) {
        return new DecodeResult(args, List.copyOf(warnings), List.copyOf(alternateStructures),
            strategy);
    }
}
