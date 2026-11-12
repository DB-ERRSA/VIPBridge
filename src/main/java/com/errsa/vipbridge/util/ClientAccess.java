package com.errsa.vipbridge.util;

import com.errsa.vipbridge.VIPBridgePlugin;
import org.bukkit.entity.Player;

/** Centralizes client-specific feature gates so Bedrock never opens Java-only reward GUIs. */
public final class ClientAccess {
    private ClientAccess() {}

    public static boolean tokenGuisSupported(VIPBridgePlugin plugin, Player player) {
        if (!plugin.getConfig().getBoolean("client-support.java-only-token-guis", true)) return true;
        return !BedrockLinkForm.isBedrock(player, plugin);
    }

    public static boolean requireTokenGuiSupport(VIPBridgePlugin plugin, Player player) {
        if (tokenGuisSupported(plugin, player)) return true;
        sendJavaOnlyNotice(plugin, player);
        return false;
    }

    public static void sendJavaOnlyNotice(VIPBridgePlugin plugin, Player player) {
        String message = plugin.getConfig().getString(
                "client-support.bedrock-message",
                "&eCustom Rewards are currently supported on the &fJava Edition client &eonly. &7Your tokens remain safely saved and can be used whenever you join on Java."
        );
        player.sendMessage(plugin.prefix() + Text.color(message));
    }
}
