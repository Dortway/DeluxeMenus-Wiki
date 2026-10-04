package com.exoblacksmith.util;

/**
 * Converts plain lowercase ASCII letters to unicode small capitals while leaving
 * MiniMessage tags ({@code <...>}) and placeholders untouched.
 */
public final class SmallCaps {
    private static final String FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕᴛᴜᴠᴡxʏᴢ";

    private SmallCaps() {
    }

    public static String convert(String input) {
        StringBuilder out = new StringBuilder(input.length());
        int depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
                out.append(c);
                continue;
            }
            if (depth == 0) {
                int idx = FROM.indexOf(c);
                out.append(idx >= 0 ? TO.charAt(idx) : c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
