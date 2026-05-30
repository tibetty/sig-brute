package me.tibetty.sigbrute.decode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Solidity signature shape (arg count, tuple nesting, array suffixes). Leaf type names are ignored
 * — only structural compatibility is compared.
 */
public final class SignatureStructure {

    private final List<Node> topLevel;

    private SignatureStructure(List<Node> topLevel) {
        this.topLevel = List.copyOf(topLevel);
    }

    public static SignatureStructure parseSignature(String textSignature) {
        var trimmed = textSignature.strip();
        var lp = trimmed.indexOf('(');
        var rp = trimmed.lastIndexOf(')');
        if (lp < 0 || rp <= lp) {
            throw new IllegalArgumentException("not a function signature: " + textSignature);
        }
        var params = trimmed.substring(lp + 1, rp);
        var fields = parseParamList(params).stream().map(SignatureStructure::parseType).toList();
        return new SignatureStructure(fields);
    }

    public static SignatureStructure fromDecodedArgs(List<DecodedArg> args) {
        return new SignatureStructure(args.stream().map(SignatureStructure::fromDecodedArg).toList());
    }

    public boolean structureEquals(SignatureStructure other) {
        return nodesEqual(this.topLevel, other.topLevel);
    }

    /**
     * Top-level shape only: arg count, leaf vs tuple at each top-level slot, and array suffixes.
     * Tuple interiors are not compared — models shallow skeleton when inner field types are unknown.
     */
    public boolean topLevelShapeEquals(SignatureStructure other) {
        return topLevelNodesEqual(
            topLevelShapeOnly(this).topLevel,
            topLevelShapeOnly(other).topLevel);
    }

    public static SignatureStructure topLevelShapeFromSignature(String textSignature) {
        return topLevelShapeOnly(parseSignature(textSignature));
    }

    public static SignatureStructure topLevelShapeFromDecodedArgs(List<DecodedArg> args) {
        return topLevelShapeOnly(fromDecodedArgs(args));
    }

    private static SignatureStructure topLevelShapeOnly(SignatureStructure structure) {
        return new SignatureStructure(structure.topLevel().stream()
            .map(SignatureStructure::opaqueTupleNode)
            .toList());
    }

    private static Node opaqueTupleNode(Node node) {
        if (node.kind() == Node.Kind.TUPLE) {
            return new Node(Node.Kind.TUPLE, node.arraySuffix(), List.of());
        }
        return new Node(Node.Kind.LEAF, node.arraySuffix(), List.of());
    }

    private static boolean topLevelNodesEqual(List<Node> a, List<Node> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (var i = 0; i < a.size(); i++) {
            if (!topLevelNodeEqual(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean topLevelNodeEqual(Node a, Node b) {
        return a.kind() == b.kind() && a.arraySuffix().equals(b.arraySuffix());
    }

    public List<Node> topLevel() {
        return topLevel;
    }

    private static Node fromDecodedArg(DecodedArg arg) {
        if (arg instanceof DecodedArg.Leaf) {
            return new Node(Node.Kind.LEAF, "", List.of());
        }
        if (arg instanceof DecodedArg.PrimArray pa) {
            return new Node(Node.Kind.LEAF, pa.arraySuffix(), List.of());
        }
        if (arg instanceof DecodedArg.Tuple t) {
            var children = t.fields().stream().map(SignatureStructure::fromDecodedArg).toList();
            return new Node(Node.Kind.TUPLE, t.arraySuffix(), children);
        }
        throw new IllegalStateException("unknown DecodedArg: " + arg);
    }

    private static Node parseType(String typeStr) {
        var s = typeStr.strip();
        var arraySuffix = new StringBuilder();
        Matcher arrayMatcher;
        while ((arrayMatcher = ARRAY_SUFFIX.matcher(s)).find()) {
            arraySuffix.insert(0, arrayMatcher.group());
            s = s.substring(0, arrayMatcher.start()).strip();
        }
        if (s.startsWith("(")) {
            if (!s.endsWith(")")) {
                throw new IllegalArgumentException("unbalanced tuple: " + typeStr);
            }
            var inner = s.substring(1, s.length() - 1);
            var fields = parseParamList(inner).stream().map(SignatureStructure::parseType).toList();
            return new Node(Node.Kind.TUPLE, arraySuffix.toString(), fields);
        }
        return new Node(Node.Kind.LEAF, arraySuffix.toString(), List.of());
    }

    private static List<String> parseParamList(String params) {
        var parts = new ArrayList<String>();
        var depth = 0;
        var start = 0;
        for (var i = 0; i < params.length(); i++) {
            var ch = params.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                parts.add(params.substring(start, i).strip());
                start = i + 1;
            }
        }
        var tail = params.substring(start).strip();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static boolean nodesEqual(List<Node> a, List<Node> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (var i = 0; i < a.size(); i++) {
            if (!nodeEqual(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean nodeEqual(Node a, Node b) {
        if (a.kind() != b.kind() || !a.arraySuffix().equals(b.arraySuffix())) {
            return false;
        }
        return nodesEqual(a.children(), b.children());
    }

    private static final Pattern ARRAY_SUFFIX = Pattern.compile("\\[[^\\]]*\\]$");

    public record Node(Kind kind, String arraySuffix, List<Node> children) {
        public enum Kind {
            LEAF, TUPLE
        }

        public Node {
            children = List.copyOf(children);
        }
    }
}
