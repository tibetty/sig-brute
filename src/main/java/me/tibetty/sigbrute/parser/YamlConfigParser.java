package me.tibetty.sigbrute.parser;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.tibetty.sigbrute.model.ArgSpec;
import me.tibetty.sigbrute.model.LeafArgSpec;
import me.tibetty.sigbrute.model.SearchConfig;
import me.tibetty.sigbrute.model.TupleArgSpec;
import me.tibetty.sigbrute.util.HexUtil;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public class YamlConfigParser {

    private static final Pattern ARRAY_SUFFIX = Pattern.compile("^(\\[\\]|\\[\\d+\\])$");

    public SearchConfig parse(InputStream in) {
        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(in);

        var selectorStr = require(root, "selector");
        var selector = HexUtil.fromHex(selectorStr);
        if (selector.length != 4) {
            throw new IllegalArgumentException("selector must be exactly 4 bytes");
        }

        Object namesRaw = root.get("method_names");
        if (namesRaw == null) {
            throw new IllegalArgumentException("Missing 'method_names'");
        }

        var methodNames = namesRaw instanceof String s ? List.of(s)
            : ((List<?>) namesRaw).stream().map(Object::toString).toList();

        var argsRaw = (List<?>) root.get("args");
        if (argsRaw == null) {
            throw new IllegalArgumentException("Missing 'args'");
        }

        var args = argsRaw.stream().map(this::parseArgSpec).toList();

        var parallelism = root.containsKey("parallelism") ? (int) root.get("parallelism")
            : Runtime.getRuntime().availableProcessors();

        var findFirst = Boolean.TRUE.equals(root.get("find_first"));

        var shardIndex = root.containsKey("shard_index") ? (int) root.get("shard_index") : 0;

        var totalShards = root.containsKey("total_shards") ? (int) root.get("total_shards") : 1;

        return new SearchConfig(selector, methodNames, args, parallelism, findFirst, shardIndex,
            totalShards);
    }

    @SuppressWarnings("unchecked")
    private ArgSpec parseArgSpec(Object raw) {
        // Bare list: - [address, bytes20]
        if (raw instanceof List<?> list) {
            return new LeafArgSpec(list.stream().map(Object::toString).toList());
        }

        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Arg spec must be a list or mapping, got: " + raw);
        }

        var map = (Map<String, Object>) rawMap;

        // - (): plain tuple — (T1,T2,...)
        // - "()[]": dynamic tuple array — (T1,T2,...)[]
        // - "()[N]": fixed tuple array — (T1,T2,...)[N]
        var tupleEntry = findUniqueEntry(map, k -> k.startsWith("()"), "'()'");
        if (tupleEntry != null) {
            var tupleKey = tupleEntry.getKey();
            var suffix = tupleKey.substring(2);
            if (!suffix.isEmpty() && !ARRAY_SUFFIX.matcher(suffix).matches()) {
                throw new IllegalArgumentException(
                    "Invalid array suffix in tuple key '" + tupleKey + "'");
            }
            return parseTupleFields(tupleEntry.getValue(), suffix);
        }

        // - "[]": dynamic primitive array — T[]
        // - "[N]": fixed primitive array — T[N]
        var arrayEntry = findUniqueEntry(map, k -> ARRAY_SUFFIX.matcher(k).matches(), "'[]'");
        if (arrayEntry != null) {
            var arrayKey = arrayEntry.getKey();
            if (!(arrayEntry.getValue() instanceof List<?> bases)) {
                throw new IllegalArgumentException(
                    "'" + arrayKey + "' value must be a list of base type patterns");
            }
            var patterns = bases.stream().flatMap(b -> b instanceof List<?> sub
                ? sub.stream().map(Object::toString) : Stream.of(b.toString()))
                .map(base -> base + arrayKey).toList();
            return new LeafArgSpec(patterns);
        }

        throw new IllegalArgumentException(
            "Arg spec must be a list, or a mapping with '()' or '[]'; got keys: " + map.keySet());
    }

    // .toString() is intentionally defensive: SnakeYAML may produce non-String keys (e.g. an
    // empty List) when the user writes an unquoted [] key. Using toString() normalises both cases.
    private static Map.Entry<String, Object> findUniqueEntry(Map<String, Object> map,
        Predicate<String> test, String keyDesc) {
        Map.Entry<String, Object> found = null;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (test.test(e.getKey())) {
                if (found != null) {
                    throw new IllegalArgumentException(
                        "Arg spec has multiple " + keyDesc + " keys; got: " + map.keySet());
                }
                found = e;
            }
        }
        return found;
    }

    private TupleArgSpec parseTupleFields(Object raw, String suffix) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                "Tuple value must be a list of field specs, got: " + raw);
        }

        var fields = list.stream().map(this::parseArgSpec).toList();
        return new TupleArgSpec(fields, suffix);
    }

    private static String require(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required field: '" + key + "'");
        }

        return v.toString();
    }
}
