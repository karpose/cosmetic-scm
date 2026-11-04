package com.cosmetic.scm.domain.rules;

import java.security.SecureRandom;

/**
 * 防伪码生成：12 位 Crockford Base32（不含 I、L、O、U，避免看错），约 60 位随机熵，无法靠枚举猜中。
 * 展示和印刷时按 4 位一组加短横线，如 7K3M-Q9TX-2B8R；查询时忽略大小写、空格和短横线。
 */
public final class SerialCodes {

    public static final int LENGTH = 12;
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private SerialCodes() {
    }

    public static String random() {
        char[] c = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            c[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(c);
    }

    /** 规范化用户输入：去掉空格和短横线、转大写，把容易混淆的 O、I、L 当作 0、1、1。 */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[\\s-]", "").toUpperCase().replace('O', '0').replace('I', '1').replace('L', '1');
    }

    public static boolean wellFormed(String normalized) {
        if (normalized.length() != LENGTH) {
            return false;
        }
        for (char ch : normalized.toCharArray()) {
            if (new String(ALPHABET).indexOf(ch) < 0) {
                return false;
            }
        }
        return true;
    }

    public static String display(String code) {
        return code.length() == LENGTH ? code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8) : code;
    }
}
