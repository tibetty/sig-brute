#!/usr/bin/env python3
"""One-shot split of monolithic AbiDecoder.java into focused classes."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DECODE = ROOT / "src/main/java/me/tibetty/sigbrute/decode"

MONOLITH = DECODE / "AbiDecoder.java"
if len(sys.argv) > 1:
    MONOLITH = Path(sys.argv[1])
if not MONOLITH.is_file():
    raise SystemExit(f"monolithic AbiDecoder not found: {MONOLITH}")

lines = MONOLITH.read_text().splitlines(keepends=True)
if len(lines) < 500:
    raise SystemExit(
        f"{MONOLITH} looks like the facade ({len(lines)} lines); "
        "pass path to monolithic source, e.g. /tmp/AbiDecoder.java.bak"
    )


def L(a: int, b: int) -> str:
    return "".join(lines[a - 1 : b])


def _is_method_decl(line: str, name: str) -> bool:
    return bool(
        re.search(
            rf"^\s+(?:private\s+)?static\s+[\w.<>\[\]]+\s+{re.escape(name)}\s*\(",
            line,
        )
    )


def package_visible(text: str) -> str:
    """Package-private helpers (same package) — drop private on static methods and records."""
    text = re.sub(r"^    private static ", "    static ", text, flags=re.MULTILINE)
    text = re.sub(r"\n    private static ", "\n    static ", text)
    text = re.sub(r"^    private record ", "    record ", text, flags=re.MULTILINE)
    text = re.sub(r"\n    private record ", "\n    record ", text)
    return text


def sub_external(text: str) -> str:
    mapping = [
        ("slice", "AbiCodec.slice"),
        ("uintOf", "AbiCodec.uintOf"),
        ("safeToInt", "AbiCodec.safeToInt"),
        ("isPlausibleOffset", "AbiCodec.isPlausibleOffset"),
        ("looksLikeOffsetAt", "AbiCodec.looksLikeOffsetAt"),
        ("nextDynOffsetAfter", "AbiCodec.nextDynOffsetAfter"),
        ("scanHeadSize", "AbiCodec.scanHeadSize"),
        ("isDynamicBytesOrString", "AbiTypeSyntax.isDynamicBytesOrString"),
        ("isDynamicHint", "AbiTypeSyntax.isDynamicHint"),
        ("staticSlotCount", "AbiTypeSyntax.staticSlotCount"),
        ("isStaticPrimitive", "AbiTypeSyntax.isStaticPrimitive"),
        ("isBytesN", "AbiTypeSyntax.isBytesN"),
        ("splitArraySuffix", "AbiTypeSyntax.splitArraySuffix"),
        ("countFixedArrayElements", "AbiTypeSyntax.countFixedArrayElements"),
        ("isAsciiDigits", "AbiTypeSyntax.isAsciiDigits"),
        ("staticInlineTupleHeadSlots", "AbiTypeSyntax.staticInlineTupleHeadSlots"),
        ("isConcreteUint", "UintPatternCompactor.isConcreteUint"),
        ("compactUintFloor", "UintPatternCompactor.compact"),
        ("decodeTupleBody", "TupleBodyDecoder.decode"),
        ("decodeDynamicField", "TupleBodyDecoder.decodeDynamicField"),
        ("parseMonotonicOffsetTable", "TupleBodyDecoder.parseMonotonicOffsetTable"),
    ]
    for name, qual in mapping:
        out: list[str] = []
        for line in text.splitlines(keepends=True):
            if _is_method_decl(line, name):
                out.append(line)
            else:
                out.append(re.sub(rf"\b{re.escape(name)}\(", f"{qual}(", line))
        text = "".join(out)
    text = text.replace('private static final String BYTES = "bytes";', "")
    text = text.replace('private static final String STRING = "string";', "")
    text = text.replace(
        'private static final String UINT_WILDCARD = "uint*";',
        "private static final String UINT_WILDCARD = UintPatternCompactor.UINT_WILDCARD;",
    )
    text = text.replace("type.equals(TUPLE)", "type.equals(SkeletonTypes.TUPLE)")
    text = text.replace("type.equals(BYTES)", "type.equals(AbiTypeSyntax.BYTES)")
    text = text.replace("type.equals(STRING)", "type.equals(AbiTypeSyntax.STRING)")
    return text


# AbiCodec — low-level word/offset helpers
abi_codec_src = package_visible(L(577, 594) + L(825, 922) + L(1307, 1328))
(DECODE / "AbiCodec.java").write_text(
    """package me.tibetty.sigbrute.decode;

import java.math.BigInteger;
import java.util.List;

/** Low-level ABI word access: slicing, uint decoding, and offset plausibility. */
final class AbiCodec {

    private AbiCodec() {
    }

"""
    + abi_codec_src
    + "}\n"
)

# AbiTypeSyntax — type-string helpers
abi_type_src = package_visible(L(553, 566) + L(596, 659) + L(691, 693) + L(1330, 1463))
abi_type_src = abi_type_src.replace("type.equals(TUPLE)", "type.equals(SkeletonTypes.TUPLE)")
(DECODE / "AbiTypeSyntax.java").write_text(
    """package me.tibetty.sigbrute.decode;

/** Solidity type-string helpers for skeleton slot counting and dynamic detection. */
final class AbiTypeSyntax {

    static final String BYTES = "bytes";
    static final String STRING = "string";

    private AbiTypeSyntax() {
    }

"""
    + abi_type_src
    + "}\n"
)

# UintPatternCompactor
uint_src = L(1175, 1288) + L(1149, 1155)
uint_src = uint_src.replace("compactUintFloor", "compact")
uint_src = re.sub(r"\bisAsciiDigits\(", "AbiTypeSyntax.isAsciiDigits(", uint_src)
uint_src = package_visible(uint_src)
(DECODE / "UintPatternCompactor.java").write_text(
    """package me.tibetty.sigbrute.decode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.tibetty.sigbrute.expander.TypeExpander;

/** Collapses consecutive uint candidate lists into compact YAML patterns. */
final class UintPatternCompactor {

    static final String UINT_WILDCARD = "uint*";

    private UintPatternCompactor() {
    }

"""
    + uint_src
    + "}\n"
)

# TupleBodyDecoder
tuple_src = sub_external(
    L(762, 774) + L(776, 824) + L(928, 1174) + L(1290, 1301)
)
tuple_src = package_visible(tuple_src.replace("decodeTupleBody", "decode", 1))
(DECODE / "TupleBodyDecoder.java").write_text(
    """package me.tibetty.sigbrute.decode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;
import me.tibetty.sigbrute.expander.TypeExpander;

/** Heuristic tuple-body decoder (no Etherscan type skeleton). */
final class TupleBodyDecoder {

    private static final String BYTES = AbiTypeSyntax.BYTES;
    private static final String STRING = AbiTypeSyntax.STRING;
    private static final String UINT_WILDCARD = UintPatternCompactor.UINT_WILDCARD;

    private TupleBodyDecoder() {
    }

"""
    + tuple_src
    + "}\n"
)

# SkeletonDecoder
skel_src = package_visible(
    sub_external(L(49, 119) + L(132, 552) + L(567, 576) + L(661, 756))
)
skel_decode = sub_external(L(122, 129))
(DECODE / "SkeletonDecoder.java").write_text(
    """package me.tibetty.sigbrute.decode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import me.tibetty.sigbrute.decode.infer.TypeInferrer;

/** Skeleton-guided top-level decode using Etherscan / 4byte type hints. */
final class SkeletonDecoder {

    private static final String BYTES = AbiTypeSyntax.BYTES;
    private static final String STRING = AbiTypeSyntax.STRING;

    private SkeletonDecoder() {
    }

"""
    + skel_src
    + """
    static List<DecodedArg> decode(byte[] body, List<String> hint) {
"""
    + skel_decode
    + """
    }

"""
    + "}\n"
)

# AbiDecoder facade
(DECODE / "AbiDecoder.java").write_text(
    """package me.tibetty.sigbrute.decode;

import java.util.List;

/**
 * Calldata decoder entry point. Delegates to {@link SkeletonDecoder} when a type skeleton is
 * available, otherwise to {@link TupleBodyDecoder} for heuristic decoding.
 *
 * @see package-info
 */
public final class AbiDecoder {

    private AbiDecoder() {
    }

    public static List<DecodedArg> decodeArgs(byte[] body, List<String> topLevelHint) {
        if ((body.length & 31) != 0) {
            throw new IllegalArgumentException("body length not a multiple of 32: " + body.length);
        }

        if (topLevelHint != null && !topLevelHint.isEmpty()) {
            return SkeletonDecoder.decode(body, topLevelHint);
        }

        return TupleBodyDecoder.decode(body);
    }

    /**
     * Collapses consecutive uint runs in candidate lists (used when merging array element types).
     */
    public static List<String> compactUintFloor(List<String> types) {
        return UintPatternCompactor.compact(types);
    }
}
"""
)

print("Split complete from", MONOLITH)
