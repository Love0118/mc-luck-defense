package dev.moma.core;

import java.math.BigDecimal;

/** Currency is stored in exact tenths; public amounts and labels are in gold. */
public final class Gold {
    private Gold() {}
    public static long units(long wholeGold) {
        if (wholeGold < 0) throw new IllegalArgumentException("Invalid gold amount");
        return Math.multiplyExact(wholeGold,10);
    }
    public static long units(double gold) {
        if (!Double.isFinite(gold) || gold < 0) throw new IllegalArgumentException("Invalid gold amount");
        return BigDecimal.valueOf(gold).movePointRight(1).longValueExact();
    }
    public static double amount(long units) { return units / 10.0; }
    public static String format(double gold) { return BigDecimal.valueOf(gold).stripTrailingZeros().toPlainString(); }
}
