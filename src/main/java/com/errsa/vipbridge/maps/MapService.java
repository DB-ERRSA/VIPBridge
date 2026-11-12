package com.errsa.vipbridge.maps;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenType;
import com.errsa.vipbridge.util.Text;
import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.objectholders.DitheringType;
import com.loohp.imageframe.objectholders.ImageMap;
import com.loohp.imageframe.objectholders.ImageMapCreationTask;
import com.loohp.imageframe.objectholders.ImageMapLoader;
import com.loohp.imageframe.objectholders.ImageMapLoaders;
import com.loohp.imageframe.objectholders.URLImageMap;
import com.loohp.imageframe.objectholders.URLImageMapCreateInfo;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import com.loohp.imageframe.libs.net.kyori.adventure.text.Component;
import com.loohp.imageframe.libs.net.kyori.adventure.text.format.NamedTextColor;
import com.loohp.imageframe.utils.ComponentUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MapService {

    private final VIPBridgePlugin plugin;
    private final YamlFile messagesFile;
    private final TokenService tokenService;
    private final MailboxService mailboxService;

    private final ConcurrentMap<String, Boolean> pendingTokens = new ConcurrentHashMap<>();

    public MapService(
            VIPBridgePlugin plugin,
            YamlFile messagesFile,
            TokenService tokenService,
            MailboxService mailboxService
    ) {
        this.plugin = plugin;
        this.messagesFile = messagesFile;
        this.tokenService = tokenService;
        this.mailboxService = mailboxService;
    }

    public void approveToken(CommandSender sender, String tokenId, String urlRaw) {
        String url = urlRaw == null ? "" : urlRaw.trim();

        if (!isAllowedImageUrl(url)) {
            int maxLength = plugin.getConfig().getInt(
                    "maps.imageframe.max-url-length",
                    224
            );

            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "map-invalid-url",
                    "&cEnter a direct HTTPS image URL ending in &e.png&c, &e.jpg&c, &e.jpeg&c, or &e.webp&c. The full URL must be &e{max} characters or less&c."
            ).replace("{max}", String.valueOf(maxLength))));

            return;
        }

        TokenService.TokenLookup lookup = tokenService.findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return;
        }

        if (tokenService.getTokenType(tokenId) != TokenType.CUSTOM_MAP) {
            send(sender, "map-invalid-token");
            return;
        }

        if (!tokenService.isProcessable(tokenId)) {
            String status = tokenService.getTokenStatus(tokenId);

            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "token-already-used",
                    "&cThat token is already marked as &e{status}&c."
            ).replace("{status}", status)));

            return;
        }

        if (pendingTokens.putIfAbsent(tokenId, Boolean.TRUE) != null) {
            sender.sendMessage(plugin.prefix() + Text.color(
                    "&cThat map token is already being processed."
            ));
            return;
        }

        String mapName = buildMapNameFromToken(tokenId);

        if (!tokenService.markMapProcessing(sender, tokenId, mapName, url)) {
            pendingTokens.remove(tokenId);
            return;
        }

        UUID ownerUuid = UUID.fromString(lookup.ownerUuid());
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);

        String ownerName = owner.getName() == null
                ? ownerUuid.toString()
                : owner.getName();

        int width = plugin.getConfig().getInt("maps.imageframe.width", 1);
        int height = plugin.getConfig().getInt("maps.imageframe.height", 1);

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "map-processing",
                "&aImageFrame is generating map &e{map_name}&a..."
        ).replace("{map_name}", mapName)));

        if (ImageFrame.imageMapCreationTaskManager.isCreatorInQueue(ownerUuid)) {
            tokenService.resetMapTokenToUnused(
                    tokenId,
                    "Player already has another ImageFrame map processing."
            );

            pendingTokens.remove(tokenId);

            sender.sendMessage(plugin.prefix() + Text.color(
                    "&eThat player already has another custom map processing. "
                            + "Wait for it to finish, then retry this token."
            ));

            return;
        }

        try {
            ImageMapLoader<? extends URLImageMap, URLImageMapCreateInfo> loader =
                    ImageMapLoaders.getLoader(
                            URLImageMap.class,
                            URLImageMapCreateInfo.class,
                            url,
                            sender
                    );

            if (loader == null) {
                fail(sender, tokenId, "ImageFrame could not select a URL image loader.");
                return;
            }

            URLImageMapCreateInfo createInfo = new URLImageMapCreateInfo(
                    ImageFrame.imageMapManager,
                    mapName,
                    url,
                    width,
                    height,
                    DitheringType.NEAREST_COLOR,
                    ownerUuid
            );

            ImageMapCreationTask<? extends ImageMap> task =
                    ImageFrame.imageMapCreationTaskManager.enqueue(
                            ownerUuid,
                            mapName,
                            () -> loader.create(createInfo).get()
                    );

            task.whenComplete((imageMap, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (throwable != null || imageMap == null) {
                    fail(sender, tokenId, safeReason(throwable));
                    task.complete(imageFrameFailureMessage());
                    return;
                }

                boolean delivered = finalizeMapDelivery(
                        sender,
                        tokenId,
                        mapName,
                        ownerUuid,
                        ownerName,
                        imageMap
                );

                if (delivered) {
                    task.complete(imageFrameSuccessMessage());
                } else {
                    task.complete(imageFrameFailureMessage());
                }
            }));
        } catch (Exception ex) {
            fail(sender, tokenId, safeReason(ex));
        }
    }

    private boolean finalizeMapDelivery(
            CommandSender sender,
            String tokenId,
            String mapName,
            UUID ownerUuid,
            String ownerName,
            ImageMap imageMap
    ) {
        boolean registered = false;

        try {
            ImageFrame.imageMapManager.addMap(imageMap);
            registered = true;

            ItemStack combinedMap = ImageFrame.combinedMapItemHandler.getCombinedMap(imageMap);

            if (combinedMap == null || combinedMap.getType() == Material.AIR) {
                throw new IllegalStateException("ImageFrame did not generate a Combined ImageMap item.");
            }

            mailboxService.addItem(
                    ownerUuid,
                    ownerName,
                    combinedMap,
                    "approved-image-map:" + mapName
            );

            tokenService.fulfillMapToken(
                    sender,
                    tokenId,
                    mapName,
                    imageMap.getImageIndex()
            );

            pendingTokens.remove(tokenId);

            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                            "map-delivered",
                            "&aFinished map &e{map_name} &awas automatically delivered to &e{player}&a's mailbox."
                    )
                    .replace("{map_name}", mapName)
                    .replace("{player}", ownerName)));
            return true;
        } catch (Exception ex) {
            if (registered && imageMap.getImageIndex() >= 0) {
                try {
                    ImageFrame.imageMapManager.deleteMap(imageMap.getImageIndex());
                } catch (Exception cleanupEx) {
                    plugin.getLogger().warning(
                            "Could not delete failed ImageFrame map "
                                    + mapName
                                    + ": "
                                    + cleanupEx.getMessage()
                    );
                }
            }

            fail(sender, tokenId, safeReason(ex));
            return false;
        }
    }

    private boolean isAllowedImageUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }

        int maxLength = plugin.getConfig().getInt(
                "maps.imageframe.max-url-length",
                224
        );

        if (raw.length() > maxLength) {
            return false;
        }

        try {
            URI uri = URI.create(raw);

            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }

            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return false;
            }

            String path = uri.getPath();

            if (path == null) {
                return false;
            }

            String lowerPath = path.toLowerCase(Locale.ROOT);

            List<String> allowed = plugin.getConfig().getStringList(
                    "maps.imageframe.allowed-extensions"
            );

            if (allowed.isEmpty()) {
                allowed = List.of(".png", ".jpg", ".jpeg", ".webp");
            }

            for (String extension : allowed) {
                if (lowerPath.endsWith(extension.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }

            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private String buildMapNameFromToken(String tokenId) {
        String clean = tokenId.toLowerCase(Locale.ROOT)
                .replace("map-", "")
                .replaceAll("[^a-z0-9_\\-]", "_");

        return "vipmap_" + clean;
    }

    private void fail(CommandSender sender, String tokenId, String reason) {
        tokenService.resetMapTokenToUnused(tokenId, reason);
        pendingTokens.remove(tokenId);

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "map-failed",
                "&cMap generation failed: &e{reason}&c. The token was reset to UNUSED so staff can retry."
        ).replace("{reason}", reason)));
    }

    private String safeReason(Throwable throwable) {
        if (throwable == null) {
            return "Unknown ImageFrame error";
        }

        Throwable cause = throwable.getCause() == null
                ? throwable
                : throwable.getCause();

        String message = cause.getMessage();

        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }

    private void send(CommandSender sender, String path) {
        sender.sendMessage(plugin.prefix() + Text.color(
                messagesFile.getConfig().getString(path, path)
        ));
    }

    private Component imageFrameSuccessMessage() {
        return ComponentUtils.translatable(
                "imageframe.messages.image_map_created"
        ).color(NamedTextColor.GREEN);
    }

    private Component imageFrameFailureMessage() {
        return ComponentUtils.translatable(
                "imageframe.messages.unable_to_load_map"
        ).color(NamedTextColor.RED);
    }

}