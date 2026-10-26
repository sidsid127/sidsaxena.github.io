package com.sidsaxena.daybook;

/** Barcodes for loyalty cards: Code 128 (letters and digits) and EAN-13. Returns bar modules (true = black). */
final class Barcode {
    private Barcode() {}

    private static final int[] C128 = {
            0x6cc, 0x66c, 0x666, 0x498, 0x48c, 0x44c, 0x4c8, 0x4c4, 0x464, 0x648, 0x644, 0x624, 0x59c, 0x4dc, 0x4ce,
            0x5cc, 0x4ec, 0x4e6, 0x672, 0x65c, 0x64e, 0x6e4, 0x674, 0x76e, 0x74c, 0x72c, 0x726, 0x764, 0x734, 0x732,
            0x6d8, 0x6c6, 0x636, 0x518, 0x458, 0x446, 0x588, 0x468, 0x462, 0x688, 0x628, 0x622, 0x5b8, 0x58e, 0x46e,
            0x5d8, 0x5c6, 0x476, 0x776, 0x68e, 0x62e, 0x6e8, 0x6e2, 0x6ee, 0x758, 0x746, 0x716, 0x768, 0x762, 0x71a,
            0x77a, 0x642, 0x78a, 0x530, 0x50c, 0x4b0, 0x486, 0x42c, 0x426, 0x590, 0x584, 0x4d0, 0x4c2, 0x434, 0x432,
            0x612, 0x650, 0x7ba, 0x614, 0x47a, 0x53c, 0x4bc, 0x49e, 0x5e4, 0x4f4, 0x4f2, 0x7a4, 0x794, 0x792, 0x6de,
            0x6f6, 0x7b6, 0x578, 0x51e, 0x45e, 0x5e8, 0x5e2, 0x7a8, 0x7a2, 0x5de, 0x5ee, 0x75e, 0x7ae, 0x684, 0x690,
            0x69c};
    private static final int STOP = 0x18eb; // 13 modules

    /** Code 128 set B (or set C for an even number of digits, which is shorter). Null if not encodable. */
    static boolean[] code128(String text) {
        if (text.isEmpty()) return null;
        boolean digits = text.matches("\\d+") && text.length() % 2 == 0;
        int[] codes = new int[digits ? text.length() / 2 + 1 : text.length() + 1];
        codes[0] = digits ? 105 : 104;
        for (int i = 1; i < codes.length; i++) {
            if (digits) {
                codes[i] = Integer.parseInt(text.substring((i - 1) * 2, (i - 1) * 2 + 2));
            } else {
                char ch = text.charAt(i - 1);
                if (ch < 32 || ch > 126) return null;
                codes[i] = ch - 32;
            }
        }
        int sum = codes[0];
        for (int i = 1; i < codes.length; i++) sum += codes[i] * i;
        StringBuilder bits = new StringBuilder();
        for (int c : codes) bits.append(bits(C128[c], 11));
        bits.append(bits(C128[sum % 103], 11)).append(bits(STOP, 13));
        return toModules(bits);
    }

    private static final String[] L = {"0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011"};
    private static final String[] G = {"0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111"};
    private static final String[] R = {"1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100"};
    private static final String[] PARITY = {"LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"};

    /** EAN-13 from 12 or 13 digits (the check digit is computed / verified). Null if invalid. */
    static boolean[] ean13(String text) {
        if (!text.matches("\\d{12,13}")) return null;
        int check = ean13Check(text.substring(0, 12));
        if (text.length() == 13 && text.charAt(12) - '0' != check) return null;
        String d = text.substring(0, 12) + check;
        StringBuilder b = new StringBuilder("101");
        String parity = PARITY[d.charAt(0) - '0'];
        for (int i = 1; i <= 6; i++) b.append(parity.charAt(i - 1) == 'L' ? L[d.charAt(i) - '0'] : G[d.charAt(i) - '0']);
        b.append("01010");
        for (int i = 7; i <= 12; i++) b.append(R[d.charAt(i) - '0']);
        b.append("101");
        return toModules(b);
    }

    static int ean13Check(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (twelve.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return (10 - sum % 10) % 10;
    }

    private static String bits(int value, int width) {
        StringBuilder s = new StringBuilder(Integer.toBinaryString(value));
        while (s.length() < width) s.insert(0, '0');
        return s.toString();
    }

    private static boolean[] toModules(CharSequence bits) {
        boolean[] out = new boolean[bits.length()];
        for (int i = 0; i < out.length; i++) out[i] = bits.charAt(i) == '1';
        return out;
    }
}
