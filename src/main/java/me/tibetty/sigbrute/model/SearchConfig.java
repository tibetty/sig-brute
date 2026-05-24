package me.tibetty.sigbrute.model;

import java.util.Arrays;
import java.util.List;

public record SearchConfig(byte[] selector, List<String> methodNames, List<ArgSpec> args,
    int parallelism, boolean findFirst, int shardIndex, int totalShards) {
    /** Backward-compatible 5-arg convenience constructor (shardIndex=0, totalShards=1). */
    public SearchConfig(byte[] selector, List<String> methodNames, List<ArgSpec> args,
        int parallelism, boolean findFirst) {
        this(selector, methodNames, args, parallelism, findFirst, 0, 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof SearchConfig that)) {
            return false;
        }

        return Arrays.equals(selector, that.selector) && methodNames.equals(that.methodNames)
            && args.equals(that.args) && parallelism == that.parallelism
            && findFirst == that.findFirst && shardIndex == that.shardIndex
            && totalShards == that.totalShards;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(selector);
        result = 31 * result + methodNames.hashCode();
        result = 31 * result + args.hashCode();
        result = 31 * result + parallelism;
        result = 31 * result + Boolean.hashCode(findFirst);
        result = 31 * result + shardIndex;
        result = 31 * result + totalShards;
        return result;
    }

    @Override
    public String toString() {
        return "SearchConfig[selector=" + Arrays.toString(selector) + ", methodNames=" + methodNames
            + ", args=" + args + ", parallelism=" + parallelism + ", findFirst=" + findFirst
            + ", shardIndex=" + shardIndex + ", totalShards=" + totalShards + "]";
    }
}
