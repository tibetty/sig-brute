package me.tibetty.sigbrute.util;

public final class HexUtil {

    private HexUtil() {
    }

    public static byte[] fromHex(String hex) {
        var s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex: " + hex);
        }

        var out = new byte[s.length() / 2];
        for (var i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex char in: " + hex);
            }

            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String toHex(byte[] b) {
        var sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(String.format("%02x", v & 0xFF));
        }
        return sb.toString();
    }
}
