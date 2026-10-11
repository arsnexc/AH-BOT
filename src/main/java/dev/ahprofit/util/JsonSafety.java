package dev.ahprofit.util;

/** Bounds JSON nesting before handing input to the recursive JSON/item parsers. */
public final class JsonSafety {
    private JsonSafety() {
    }

    public static boolean hasSafeNesting(String body, int maximumDepth) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{' || character == '[') {
                if (++depth > maximumDepth) {
                    return false;
                }
            } else if (character == '}' || character == ']') {
                if (--depth < 0) {
                    return false;
                }
            }
        }
        return !inString && depth == 0;
    }

}
