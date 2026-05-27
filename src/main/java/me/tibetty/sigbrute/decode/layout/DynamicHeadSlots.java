package me.tibetty.sigbrute.decode.layout;

import java.util.ArrayList;
import java.util.List;
import me.tibetty.sigbrute.decode.abi.AbiCodec;

/** Plausible dynamic head slots in a tuple head section. */
public final class DynamicHeadSlots {

    private DynamicHeadSlots() {
    }

    public static List<int[]> collect(byte[] body, int numFields, int headSize) {
        var dynSlots = new ArrayList<int[]>();
        for (var i = 0; i < numFields; i++) {
            var value = AbiCodec.uintOf(AbiCodec.slice(body, i * 32, 32));
            if (AbiCodec.isPlausibleOffset(value, body.length, headSize)) {
                dynSlots.add(new int[]{i, AbiCodec.safeToInt(value, "dynSlots offset [" + i + "]")});
            }
        }
        return dynSlots;
    }
}
