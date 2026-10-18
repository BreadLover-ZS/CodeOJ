package com.codeoj.codeojbackendmodel.model.codesandbox;

/** Version 1: CRLF -> LF; ignore exactly one final LF; preserve every other byte of whitespace. */
public final class TextOutputComparator {
    private TextOutputComparator() { }

    public static String normalize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Missing output");
        }
        String normalized = text.replace("\r\n", "\n");
        return normalized.endsWith("\n") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    public static boolean matches(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }
}
