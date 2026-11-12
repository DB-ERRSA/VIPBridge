package com.errsa.vipbridge.util;

import org.bukkit.configuration.file.FileConfiguration;

public enum ClientType {
    JAVA,
    BEDROCK;

    public static ClientType detect(String playerName, FileConfiguration config) {
        String prefix = config.getString("client-detection.bedrock-prefix", ".");

        if (playerName != null && playerName.startsWith(prefix)) {
            return BEDROCK;
        }

        return JAVA;
    }
}