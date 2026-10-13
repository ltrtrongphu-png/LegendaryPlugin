package com.legendary.plugin.util;

/**
 * Packs a block position (x,y,z) into a single long for cheap use as a
 * HashSet/HashMap key (e.g. "already revealed this block to this player").
 * Ported from AntiESPUltimate's CoordCodec, unchanged logic - 26 bits for
 * x/z (+-33M blocks), 12 bits for y (-2048..2047, covers 1.18+ world height).
 */
public final class CoordCodec {

    private static final int Y_BITS = 12;
    private static final int XZ_BITS = 26;

    private CoordCodec() {}

    public static long pack(int x, int y, int z) {
        long lx = ((long) x) & ((1L << XZ_BITS) - 1);
        long ly = ((long) y) & ((1L << Y_BITS) - 1);
        long lz = ((long) z) & ((1L << XZ_BITS) - 1);
        return (lx << (Y_BITS + XZ_BITS)) | (ly << XZ_BITS) | lz;
    }

    public static int unpackX(long packed) {
        return signExtend((int) (packed >>> (Y_BITS + XZ_BITS)), XZ_BITS);
    }

    public static int unpackY(long packed) {
        return signExtend((int) (packed >>> XZ_BITS) & ((1 << Y_BITS) - 1), Y_BITS);
    }

    public static int unpackZ(long packed) {
        return signExtend((int) (packed & ((1L << XZ_BITS) - 1)), XZ_BITS);
    }

    private static int signExtend(int value, int bits) {
        int shift = 32 - bits;
        return (value << shift) >> shift;
    }
}
