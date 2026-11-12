package com.errsa.vipbridge.util;

import com.errsa.vipbridge.VIPBridgePlugin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;

/** Reflection bridge for Floodgate/Cumulus forms without a hard compile dependency. */
public final class BedrockForms {
    private BedrockForms() {}

    public static boolean sendSimple(VIPBridgePlugin plugin, Player player, String title, String content,
                                     java.util.List<FormButton> buttons) {
        if (!BedrockLinkForm.isBedrock(player, plugin)) return false;
        try {
            Class<?> formClass = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Object builder = formClass.getMethod("builder").invoke(null);
            invokeString(builder, "title", title);
            invokeString(builder, "content", content == null ? "" : content);

            Method buttonMethod = null;
            for (Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("button") && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class
                        && Consumer.class.isAssignableFrom(m.getParameterTypes()[1])) {
                    buttonMethod = m; break;
                }
            }
            if (buttonMethod == null) return false;
            for (FormButton b : buttons) {
                Consumer<Object> handler = ignored -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) b.action().run();
                });
                if (b.imageUrl() != null && !b.imageUrl().isBlank() && !invokeImageButton(builder, b.text(), b.imageUrl(), handler)) {
                    buttonMethod.invoke(builder, b.text(), handler);
                } else if (b.imageUrl() == null || b.imageUrl().isBlank()) {
                    buttonMethod.invoke(builder, b.text(), handler);
                }
            }
            Object form = builder.getClass().getMethod("build").invoke(builder);
            return sendForm(player, form);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not open Bedrock SimpleForm for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    public static boolean sendInput(VIPBridgePlugin plugin, Player player, String title, String label,
                                    String inputLabel, String placeholder, Consumer<String> response) {
        if (!BedrockLinkForm.isBedrock(player, plugin)) return false;
        try {
            Class<?> formClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object builder = formClass.getMethod("builder").invoke(null);
            invokeString(builder, "title", title);
            if (label != null && !label.isBlank()) invokeString(builder, "label", label);
            invokeStrings(builder, "input", inputLabel, placeholder == null ? "" : placeholder, "");

            Method valid = findConsumerMethod(builder, "validResultHandler");
            if (valid == null) return false;
            Consumer<Object> handler = result -> {
                String value = "";
                try {
                    Method asInput = result.getClass().getMethod("asInput");
                    Object v = asInput.invoke(result);
                    if (v != null) value = v.toString();
                } catch (Throwable ignored) {}
                String finalValue = value;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) response.accept(finalValue);
                });
            };
            valid.invoke(builder, handler);
            Object form = builder.getClass().getMethod("build").invoke(builder);
            return sendForm(player, form);
        } catch (Throwable ex) {
            plugin.getLogger().warning("Could not open Bedrock CustomForm for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean invokeImageButton(Object builder, String text, String imageUrl, Consumer<Object> handler) {
        try {
            Class<?> typeClass = Class.forName("org.geysermc.cumulus.util.FormImage$Type");
            Object urlType = Enum.valueOf((Class<? extends Enum>) typeClass.asSubclass(Enum.class), "URL");
            for (Method m : builder.getClass().getMethods()) {
                if (!m.getName().equals("button") || m.getParameterCount() != 4) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p[0] == String.class && p[1].isAssignableFrom(typeClass) && p[2] == String.class
                        && Consumer.class.isAssignableFrom(p[3])) {
                    m.invoke(builder, text, urlType, imageUrl, handler);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Method findConsumerMethod(Object target, String name) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && Consumer.class.isAssignableFrom(m.getParameterTypes()[0])) return m;
        }
        return null;
    }

    private static boolean sendForm(Player player, Object form) throws Exception {
        Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
        Object api = apiClass.getMethod("getInstance").invoke(null);
        for (Method method : apiClass.getMethods()) {
            if (!method.getName().equals("sendForm") || method.getParameterCount() != 2) continue;
            Class<?>[] params = method.getParameterTypes();
            if (!params[0].isAssignableFrom(UUID.class) && !UUID.class.isAssignableFrom(params[0])) continue;
            if (!params[1].isAssignableFrom(form.getClass())) continue;
            Object result = method.invoke(api, player.getUniqueId(), form);
            return !(result instanceof Boolean b) || b;
        }
        return false;
    }

    private static void invokeString(Object target, String name, String value) throws Exception {
        target.getClass().getMethod(name, String.class).invoke(target, value);
    }

    private static void invokeStrings(Object target, String name, String... values) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != values.length) continue;
            boolean ok = true;
            for (Class<?> p : method.getParameterTypes()) if (p != String.class) { ok = false; break; }
            if (ok) { method.invoke(target, (Object[]) values); return; }
        }
        throw new NoSuchMethodException(name);
    }

    public record FormButton(String text, String imageUrl, Runnable action) {
        public FormButton(String text, Runnable action) { this(text, null, action); }
    }
}
