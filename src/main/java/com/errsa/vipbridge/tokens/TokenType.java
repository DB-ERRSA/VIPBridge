package com.errsa.vipbridge.tokens;

public enum TokenType {
    CUSTOM_MAP("MAP", "custom-map", "custom-map-name", "Custom Map"),
    CUSTOM_MUSIC("MUSIC", "custom-music", "custom-music-name", "Custom Music"),
    CATCHPHRASE("CATCH", "catchphrase", "catchphrase-name", "Catchphrase"),
    HEADDB("HEAD", "head-db", "head-db-name", "HeadDB Head");

    private final String prefix;
    private final String formKey;
    private final String nameConfigKey;
    private final String displayName;

    TokenType(String prefix, String formKey, String nameConfigKey, String displayName) {
        this.prefix = prefix;
        this.formKey = formKey;
        this.nameConfigKey = nameConfigKey;
        this.displayName = displayName;
    }

    public String prefix() { return prefix; }
    public String formKey() { return formKey; }
    public String nameConfigKey() { return nameConfigKey; }
    public String displayName() { return displayName; }

    public static TokenType fromInput(String input) {
        if (input == null) return null;
        String clean = input.trim().toUpperCase().replace("-", "_");
        return switch (clean) {
            case "MAP", "CUSTOM_MAP", "CUSTOMMAP" -> CUSTOM_MAP;
            case "MUSIC", "CUSTOM_MUSIC", "CUSTOMMUSIC" -> CUSTOM_MUSIC;
            case "CATCH", "CATCHPHRASE" -> CATCHPHRASE;
            case "HEAD", "HEADDB", "HEAD_DB", "HDB" -> HEADDB;
            default -> null;
        };
    }
}
