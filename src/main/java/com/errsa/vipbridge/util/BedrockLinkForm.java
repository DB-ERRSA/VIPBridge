package com.errsa.vipbridge.util;

import com.errsa.vipbridge.VIPBridgePlugin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional Floodgate/Cumulus bridge implemented with reflection so VIPBridge can
 * still compile and run when Floodgate is not installed. Bedrock players receive
 * a CustomForm containing pre-filled text inputs, which are far easier to select
 * and copy than a Java chat click event.
 */
public final class BedrockLinkForm {
    private BedrockLinkForm() {}

    public static boolean isBedrock(Player player, VIPBridgePlugin plugin) {
        if (player == null) return false;

        // Prefer Floodgate's own player registry when its API is available.
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
            if (result instanceof Boolean bedrock) return bedrock;
        } catch (Throwable ignored) {
            // Fall back to the configured Floodgate username prefix below.
        }

        return ClientType.detect(player.getName(), plugin.getConfig()) == ClientType.BEDROCK;
    }

    /**
     * Opens a copy-friendly Bedrock form. Returns false if Floodgate/Cumulus is
     * unavailable or the installed API is incompatible, allowing the caller to
     * fall back to plain chat without breaking token redemption.
     */
    public static boolean show(VIPBridgePlugin plugin, Player player, String title,
                               String instructions, String url, String tokenId) {
        if (!plugin.getConfig().getBoolean("bedrock-link-form.enabled", true)) return false;
        if (!isBedrock(player, plugin)) return false;

        try {
            Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object builder = customFormClass.getMethod("builder").invoke(null);

            invokeStrings(builder, "title", title);
            if (instructions != null && !instructions.isBlank()) {
                invokeStrings(builder, "label", instructions);
            }

            String urlLabel = plugin.getConfig().getString("bedrock-link-form.url-field-label", "Website — select and copy");
            String tokenLabel = plugin.getConfig().getString("bedrock-link-form.token-field-label", "Token ID — select and copy");
            String placeholder = plugin.getConfig().getString("bedrock-link-form.placeholder", "Tap the field, select all, then copy");

            // Cumulus CustomForm.Builder exposes input(label, placeholder, defaultValue).
            // The default value is what makes the URL/ID immediately selectable/copyable.
            if (!invokeStrings(builder, "input", urlLabel, placeholder, url)) return false;
            if (plugin.getConfig().getBoolean("bedrock-link-form.show-token-id-field", true)) {
                invokeStrings(builder, "input", tokenLabel, placeholder, tokenId);
            }

            Object form = invokeNoArgs(builder, "build");
            if (form == null) return false;

            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            for (Method method : apiClass.getMethods()) {
                if (!method.getName().equals("sendForm") || method.getParameterCount() != 2) continue;
                Class<?>[] params = method.getParameterTypes();
                if (!params[0].isAssignableFrom(UUID.class) && !UUID.class.isAssignableFrom(params[0])) continue;
                if (!params[1].isAssignableFrom(form.getClass())) continue;
                method.invoke(api, player.getUniqueId(), form);
                return true;
            }
        } catch (Throwable ex) {
            plugin.getLogger().fine("Could not open Bedrock copy-link form for " + player.getName() + ": " + ex.getMessage());
        }
        return false;
    }

    private static boolean invokeStrings(Object target, String name, String... values) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != values.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean strings = true;
            for (Class<?> param : params) {
                if (!param.isAssignableFrom(String.class)) { strings = false; break; }
            }
            if (!strings) continue;
            method.invoke(target, (Object[]) values);
            return true;
        }
        return false;
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }
}
