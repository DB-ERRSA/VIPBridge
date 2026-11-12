package com.errsa.vipbridge.util;

import org.bukkit.ChatColor;

public final class Text {

    private Text() {
    }

    public static String color(String input) {
        if (input == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static String stripColor(String raw) {
        if (raw == null) return "";
        String stripped = ChatColor.stripColor(color(raw));
        return stripped == null ? "" : stripped;
    }

    public static String stripForId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }

        String normalized = raw.replace(".", "bedrock_");
        normalized = normalized.replaceAll("[^A-Za-z0-9_\\-/]", "_");
        normalized = normalized.toLowerCase();

        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48);
        }

        return normalized;
    }
}