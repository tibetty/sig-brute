package me.tibetty.sigbrute.decode.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import me.tibetty.sigbrute.decode.AlternateStructure;
import me.tibetty.sigbrute.decode.DecodedArg;
import me.tibetty.sigbrute.decode.DecodeResult;
import me.tibetty.sigbrute.decode.SignatureStructure;
import me.tibetty.sigbrute.decode.infer.GeneralizedTypeInferrer;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.decode.infer.WideTypeInferrer;
import me.tibetty.sigbrute.decode.strategy.body.GreedyBodyDecoder;
import me.tibetty.sigbrute.decode.strategy.body.SearchBodyDecoder;

/**
 * Per-strategy decode hooks: tuple-body recursion, dynamic tails, and static-slot inference.
 * Passed explicitly through skeleton and body decoders (no thread-local state).
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

    public static DecodeContext greedy() {
        return new DecodeContext(
            GreedyBodyDecoder::decode,
            GreedyBodyDecoder::decodeDynamicField,
            TypeInferrer::inferStatic,
            new ArrayList<>(),
            new ArrayList<>());
    }

    public static DecodeContext heuristicSearch() {
        return new DecodeContext(
            SearchBodyDecoder::decode,
            SearchBodyDecoder::decodeDynamicField,
            GeneralizedTypeInferrer::inferStatic,
            new ArrayList<>(),
            new ArrayList<>());
    }

    public static DecodeContext heuristicSearchWide() {
        return new DecodeContext(
            SearchBodyDecoder::decode,
            SearchBodyDecoder::decodeDynamicField,
            WideTypeInferrer::inferStatic,
            new ArrayList<>(),
            new ArrayList<>());
    }
}
