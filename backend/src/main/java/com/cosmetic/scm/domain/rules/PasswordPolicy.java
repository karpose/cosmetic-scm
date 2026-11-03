package com.cosmetic.scm.domain.rules;

/**
 * 密码复杂度：至少 8 位，大写、小写、数字、符号四类里至少三类，且不能包含账号名。
 * 纯规则，不依赖框架；返回 null 表示通过，否则返回给用户看的中文原因。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    public static final int MIN_CLASSES = 3;

    private PasswordPolicy() {
    }

    public static String check(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "密码至少 " + MIN_LENGTH + " 位";
        }
        if (password.length() > MAX_LENGTH) {
            return "密码最长 " + MAX_LENGTH + " 位";
        }
        int classes = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isLowerCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            classes++;
        }
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            classes++;
        }
        if (classes < MIN_CLASSES) {
            return "密码需包含大写字母、小写字母、数字、符号中的至少 " + MIN_CLASSES + " 类";
        }
        if (username != null && username.length() >= 3
                && password.toLowerCase().contains(username.toLowerCase())) {
            return "密码不能包含账号名";
        }
        return null;
    }
}
