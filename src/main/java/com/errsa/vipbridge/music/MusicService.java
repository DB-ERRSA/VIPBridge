package com.errsa.vipbridge.music;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenType;
import com.errsa.vipbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MusicService {

    private final VIPBridgePlugin plugin;
    private final YamlFile messagesFile;
    private final TokenService tokenService;
    private final MailboxService mailboxService;

    private final Set<String> pendingTokens = ConcurrentHashMap.newKeySet();
    // URLCustomDiscs' API success log does not include a reliable request id, so VIPBridge
    // intentionally serializes automated music creation. This prevents one API callback
    // from ever being attributed to the wrong premium token.
    private final AtomicReference<String> activeMusicJob = new AtomicReference<>();

    public MusicService(
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
        if (!(sender instanceof Player staff)) {
            send(sender, "music-player-only");
            return;
        }

        if (staff.getInventory().firstEmpty() == -1) {
            send(sender, "music-admin-inventory-full");
            return;
        }

        String url = urlRaw == null ? "" : urlRaw.trim();

        if (!isAllowedMp3Url(url)) {
            int maxLength = plugin.getConfig().getInt(
                    "music.url-custom-discs.max-url-length",
                    224
            );

            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "music-invalid-url",
                    "&cEnter a direct HTTPS MP3 URL ending in &e.mp3&c. The full URL must be &e{max} characters or less&c."
            ).replace("{max}", String.valueOf(maxLength))));

            return;
        }

        TokenService.TokenLookup lookup = tokenService.findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return;
        }

        if (tokenService.getTokenType(tokenId) != TokenType.CUSTOM_MUSIC) {
            send(sender, "music-invalid-token");
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

        if (!pendingTokens.add(tokenId)) {
            sender.sendMessage(plugin.prefix() + Text.color("&cThat token is already being processed."));
            return;
        }

        if (!activeMusicJob.compareAndSet(null, tokenId)) {
            pendingTokens.remove(tokenId);
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "music-another-job-active",
                    "&eAnother custom music disc is already being generated. Try this approval again when it finishes."
            )));
            return;
        }

        String discName = buildDiscNameFromToken(tokenId);

        if (!tokenService.markMusicProcessing(sender, tokenId, discName, url)) {
            finishJob(tokenId);
            return;
        }

        UUID ownerUuid = UUID.fromString(lookup.ownerUuid());
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
        String ownerName = owner.getName() == null
                ? ownerUuid.toString()
                : owner.getName();

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "music-download-started",
                "&aDownloading approved MP3 for token &e{token_id}&a..."
        ).replace("{token_id}", tokenId)));

        musicLog(tokenId, "Approval accepted for " + ownerName + "; starting MP3 download from " + safeHost(url) + ".");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Path output = null;

            try {
                String fileName = buildFileName(tokenId, discName);

                Path folder = Path.of(plugin.getConfig().getString(
                        "music.url-custom-discs.audio-folder",
                        "plugins/URLCustomDiscs/audio_to_send"
                ));

                Files.createDirectories(folder);

                output = folder.resolve(fileName);
                downloadMp3(tokenId, url, output);

                Path completedOutput = output;

                Bukkit.getScheduler().runTask(plugin, () -> beginDiscCreation(
                        staff,
                        sender,
                        tokenId,
                        discName,
                        fileName,
                        ownerUuid,
                        ownerName,
                        completedOutput
                ));
            } catch (Exception ex) {
                if (output != null) {
                    try {
                        Files.deleteIfExists(output);
                    } catch (Exception ignored) {
                    }
                }

                tokenService.resetMusicTokenToUnused(tokenId, "Download failed: " + ex.getMessage());
                finishJob(tokenId);
                musicWarn(tokenId, "Download failed: " + safeReason(ex) + ". Token reset to UNUSED.");

                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                                "music-download-failed",
                                "&cApproval failed: &e{reason} &7Token returned to &aUNUSED&7."
                        ).replace("{reason}", safeReason(ex)))
                ));
            }
        });
    }

    private void beginDiscCreation(
            Player approvingStaff,
            CommandSender sender,
            String tokenId,
            String discName,
            String fileName,
            UUID ownerUuid,
            String ownerName,
            Path sourceFile
    ) {
        Player runner = findCommandRunner(approvingStaff);
        if (runner == null) {
            int waitSeconds = Math.max(30, plugin.getConfig().getInt(
                    "music.url-custom-discs.runner-wait-timeout-seconds", 300));
            musicLog(tokenId, "MP3 is ready, but no eligible staff player is online to start URLCustomDiscs. Keeping the job alive for up to " + waitSeconds + "s.");
            sendIfOnline(sender, messagesFile.getConfig().getString(
                    "music-waiting-for-runner",
                    "&eMP3 downloaded. Waiting for an eligible staff player to be online so URLCustomDiscs can start; the token remains PROCESSING."
            ));
            waitForRunner(approvingStaff, sender, tokenId, discName, fileName, ownerUuid, ownerName, sourceFile, waitSeconds);
            return;
        }
        dispatchDiscCreation(runner, sender, tokenId, discName, fileName, ownerUuid, ownerName, sourceFile);
    }

    private void waitForRunner(
            Player approvingStaff,
            CommandSender sender,
            String tokenId,
            String discName,
            String fileName,
            UUID ownerUuid,
            String ownerName,
            Path sourceFile,
            int timeoutSeconds
    ) {
        new BukkitRunnable() {
            int elapsed;
            @Override public void run() {
                Player runner = findCommandRunner(approvingStaff);
                if (runner != null) {
                    cancel();
                    musicLog(tokenId, "Using " + runner.getName() + " to start URLCustomDiscs; approving staff does not need to remain online afterward.");
                    dispatchDiscCreation(runner, sender, tokenId, discName, fileName, ownerUuid, ownerName, sourceFile);
                    return;
                }
                if (++elapsed >= timeoutSeconds) {
                    cancel();
                    failJob(sender, tokenId,
                            "No eligible staff player became available to start URLCustomDiscs within " + timeoutSeconds + " seconds.",
                            "music-runner-timeout");
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private Player findCommandRunner(Player preferred) {
        if (preferred != null && preferred.isOnline() && preferred.getInventory().firstEmpty() != -1) return preferred;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if ((player.hasPermission("vipbridge.admin") || player.isOp()) && player.getInventory().firstEmpty() != -1) return player;
        }
        return null;
    }

    private void dispatchDiscCreation(
            Player runner,
            CommandSender sender,
            String tokenId,
            String discName,
            String fileName,
            UUID ownerUuid,
            String ownerName,
            Path sourceFile
    ) {
        Map<Integer, Integer> baseline = snapshotCustomDiscCounts(runner);
        String mode = plugin.getConfig().getString("music.url-custom-discs.mode", "mono");
        if (mode == null || mode.isBlank()) mode = "mono";

        String createCommand = plugin.getConfig().getString(
                        "music.url-custom-discs.create-command",
                        "customdisc create {file_name} {disc_name} {mode}")
                .replace("{file_name}", fileName)
                .replace("{disc_name}", discName)
                .replace("{mode}", mode);

        sendIfOnline(sender, messagesFile.getConfig().getString(
                "music-processing",
                "&aMP3 downloaded. URLCustomDiscs is generating &e{disc_name}&a... Staff may disconnect; VIPBridge will keep watching the job."
        ).replace("{disc_name}", discName));

        ApiSignal signal = attachUrlCustomDiscsSignal(tokenId);
        musicLog(tokenId, "Download complete; dispatching URLCustomDiscs create command for " + fileName + " (mode=" + mode + ", runner=" + runner.getName() + ").");
        boolean accepted = Bukkit.dispatchCommand(runner, createCommand);
        if (!accepted) {
            signal.close();
            failJob(sender, tokenId, "URLCustomDiscs rejected create command.", "music-create-command-failed");
            return;
        }
        startCaptureTask(runner, sender, tokenId, discName, fileName, ownerUuid, ownerName, sourceFile, baseline, signal);
    }

    private void startCaptureTask(
            Player runner,
            CommandSender sender,
            String tokenId,
            String discName,
            String fileName,
            UUID ownerUuid,
            String ownerName,
            Path sourceFile,
            Map<Integer, Integer> baseline,
            ApiSignal signal
    ) {
        int timeoutSeconds = Math.max(30, plugin.getConfig().getInt("music.url-custom-discs.capture-timeout-seconds", 300));
        musicLog(tokenId, "URLCustomDiscs command accepted; independently watching API/output for up to " + timeoutSeconds + "s. The staff runner may disconnect.");

        new BukkitRunnable() {
            private int secondsElapsed;
            @Override public void run() {
                // Fast path while the runner is still online: capture exactly what URLCustomDiscs gave them.
                if (runner.isOnline()) {
                    ItemStack generated = takeNewCustomDisc(runner, baseline);
                    if (generated != null) {
                        cancel(); signal.close();
                        completeJob(sender, tokenId, discName, fileName, ownerUuid, ownerName, sourceFile, generated, runner, "runner inventory");
                        return;
                    }
                }

                // Independent path: URLCustomDiscs logs [API SUCCESS] immediately before it gives the item.
                // At that point discs.json already contains the canonical model data, so VIPBridge can rebuild
                // the same disc without depending on the staff player's inventory or connection.
                if (signal.success.get()) {
                    ItemStack generated = buildDiscFromUrlCustomDiscsMetadata(discName);
                    if (generated != null) {
                        cancel(); signal.close();
                        removeMatchingGeneratedDisc(runner, generated);
                        completeJob(sender, tokenId, discName, fileName, ownerUuid, ownerName, sourceFile, generated, runner, "URLCustomDiscs API output");
                        return;
                    }
                }

                if (signal.failure.get()) {
                    cancel(); signal.close();
                    failJob(sender, tokenId, "URLCustomDiscs reported a failure: " + signal.failureReason, "music-create-command-failed");
                    return;
                }

                if (++secondsElapsed >= timeoutSeconds) {
                    cancel(); signal.close();
                    failJob(sender, tokenId, "Timed out after " + timeoutSeconds + "s waiting for URLCustomDiscs output.", "music-delivery-timeout");
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void completeJob(CommandSender sender, String tokenId, String discName, String fileName,
                             UUID ownerUuid, String ownerName, Path sourceFile, ItemStack generatedDisc,
                             Player runner, String captureSource) {
        mailboxService.addItem(ownerUuid, ownerName, generatedDisc, "approved-music-disc:" + discName);
        tokenService.fulfillMusicToken(sender, tokenId, fileName);
        finishJob(tokenId);
        if (plugin.getConfig().getBoolean("music.url-custom-discs.delete-source-after-delivery", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try { Files.deleteIfExists(sourceFile); }
                catch (Exception ex) { plugin.getLogger().warning("Could not delete temporary MP3 " + sourceFile + ": " + ex.getMessage()); }
            });
        }
        sendIfOnline(sender, messagesFile.getConfig().getString(
                "music-delivered",
                "&aFinished disc &e{disc_name} &awas automatically delivered to &e{player}&a's Deliveries."
        ).replace("{disc_name}", discName).replace("{player}", ownerName));
        musicLog(tokenId, "Generated disc captured from " + captureSource + " and delivered to " + ownerName + ". Processing complete.");
    }

    private void failJob(CommandSender sender, String tokenId, String reason, String messagePath) {
        tokenService.resetMusicTokenToUnused(tokenId, reason);
        finishJob(tokenId);
        musicWarn(tokenId, reason + " Token reset to UNUSED.");
        String configured = messagesFile.getConfig().getString(messagePath,
                "&cMusic processing failed: &e{reason} &7Token returned to &aUNUSED&7.");
        sendIfOnline(sender, configured.replace("{reason}", reason).replace("{disc_name}", buildDiscNameFromToken(tokenId)));
    }

    private void finishJob(String tokenId) {
        pendingTokens.remove(tokenId);
        activeMusicJob.compareAndSet(tokenId, null);
    }

    private void sendIfOnline(CommandSender sender, String message) {
        if (message == null || message.isBlank()) return;
        if (sender instanceof Player player && !player.isOnline()) return;
        sender.sendMessage(plugin.prefix() + Text.color(message));
    }

    private ApiSignal attachUrlCustomDiscsSignal(String tokenId) {
        if (!plugin.getConfig().getBoolean("music.url-custom-discs.independent-capture", true)) {
            musicLog(tokenId, "Independent URLCustomDiscs output capture is disabled; using runner inventory fallback only.");
            return new ApiSignal(null, null);
        }
        Plugin target = Bukkit.getPluginManager().getPlugin("URLCustomDiscs");
        if (target == null) {
            musicWarn(tokenId, "URLCustomDiscs plugin logger was not available; falling back to inventory capture until timeout.");
            return new ApiSignal(null, null);
        }
        Logger logger = target.getLogger();
        ApiSignal signal = new ApiSignal(logger, null);
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                String message = record == null ? null : record.getMessage();
                if (message == null) return;
                if (message.contains("[API SUCCESS]")) signal.success.set(true);
                if (message.contains("[API ERROR]") || message.contains("API responded with HTTP error") || message.contains("Failed to parse API response")) {
                    signal.failureReason = message;
                    signal.failure.set(true);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        signal.handler = handler;
        logger.addHandler(handler);
        return signal;
    }

    private ItemStack buildDiscFromUrlCustomDiscsMetadata(String discName) {
        try {
            Path metadata = Path.of(plugin.getConfig().getString(
                    "music.url-custom-discs.metadata-file", "plugins/URLCustomDiscs/discs.json"));
            if (!Files.exists(metadata)) return null;
            String json = Files.readString(metadata);
            String key = discName.toLowerCase(Locale.ROOT);
            Pattern objectPattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\{([^}]*)}", Pattern.DOTALL);
            Matcher objectMatcher = objectPattern.matcher(json);
            if (!objectMatcher.find()) return null;
            String object = objectMatcher.group(1);
            Matcher modelMatcher = Pattern.compile("\\\"customModelData\\\"\\s*:\\s*(\\d+)").matcher(object);
            Matcher nameMatcher = Pattern.compile("\\\"displayName\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(object);
            if (!modelMatcher.find()) return null;
            int modelData = Integer.parseInt(modelMatcher.group(1));
            String displayName = nameMatcher.find() ? nameMatcher.group(1) : discName;
            ItemStack item = new ItemStack(Material.MUSIC_DISC_13);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + displayName);
                meta.setLore(java.util.List.of(ChatColor.GRAY + displayName));
                meta.setCustomModelData(modelData);
                item.setItemMeta(meta);
            }
            return item;
        } catch (Exception ex) {
            musicWarn(discName, "Could not read URLCustomDiscs metadata yet: " + ex.getMessage());
            return null;
        }
    }

    private void removeMatchingGeneratedDisc(Player runner, ItemStack generated) {
        if (runner == null || !runner.isOnline() || generated == null || !generated.hasItemMeta()) return;
        ItemMeta wanted = generated.getItemMeta();
        if (wanted == null || !wanted.hasCustomModelData()) return;
        removeOneDiscWithModelData(runner, wanted.getCustomModelData());
    }

    private static final class ApiSignal {
        final AtomicBoolean success = new AtomicBoolean();
        final AtomicBoolean failure = new AtomicBoolean();
        volatile String failureReason = "unknown API error";
        final Logger logger;
        volatile Handler handler;
        ApiSignal(Logger logger, Handler handler) { this.logger = logger; this.handler = handler; }
        void close() {
            Handler h = handler;
            if (logger != null && h != null) {
                try { logger.removeHandler(h); } catch (Exception ignored) {}
                handler = null;
            }
        }
    }

    private Map<Integer, Integer> snapshotCustomDiscCounts(Player player) {
        Map<Integer, Integer> counts = new HashMap<>();

        for (ItemStack item : player.getInventory().getContents()) {
            if (!isCustomDisc(item)) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();

            if (meta == null) {
                continue;
            }

            int modelData = meta.getCustomModelData();

            counts.merge(modelData, item.getAmount(), Integer::sum);
        }

        return counts;
    }

    private ItemStack takeNewCustomDisc(Player player, Map<Integer, Integer> baseline) {
        Map<Integer, Integer> current = snapshotCustomDiscCounts(player);

        for (Map.Entry<Integer, Integer> entry : current.entrySet()) {
            int modelData = entry.getKey();
            int previous = baseline.getOrDefault(modelData, 0);

            if (entry.getValue() <= previous) {
                continue;
            }

            return removeOneDiscWithModelData(player, modelData);
        }

        return null;
    }

    private ItemStack removeOneDiscWithModelData(Player player, int targetModelData) {
        ItemStack[] contents = player.getInventory().getContents();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];

            if (!isCustomDisc(item)) {
                continue;
            }

            ItemMeta meta = item.getItemMeta();

            if (meta == null || meta.getCustomModelData() != targetModelData) {
                continue;
            }

            ItemStack captured = item.clone();
            captured.setAmount(1);

            if (item.getAmount() <= 1) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, item);
            }

            return captured;
        }

        return null;
    }

    private boolean isCustomDisc(ItemStack item) {
        if (item == null || item.getType() != Material.MUSIC_DISC_13) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();

        return meta != null && meta.hasCustomModelData();
    }

    private void cancelDelivery(
            CommandSender sender,
            String tokenId,
            String reason,
            String messagePath
    ) {
        tokenService.resetMusicTokenToUnused(tokenId, reason);
        finishJob(tokenId);
        musicWarn(tokenId, reason + " Token reset to UNUSED.");
        send(sender, messagePath);
    }

    private void downloadMp3(String tokenId, String urlRaw, Path output) throws Exception {
        URI uri = URI.create(urlRaw);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();

        int connectSeconds = Math.max(1, plugin.getConfig().getInt("music.url-custom-discs.connect-timeout-seconds", 15));
        int readSeconds = Math.max(1, plugin.getConfig().getInt("music.url-custom-discs.read-timeout-seconds", 60));
        int totalSeconds = Math.max(1, plugin.getConfig().getInt("music.url-custom-discs.download-timeout-seconds", 180));

        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(connectSeconds * 1000);
        connection.setReadTimeout(readSeconds * 1000);
        connection.setRequestProperty("User-Agent", "VIPBridge/1.0");

        musicLog(tokenId, "Connecting to " + safeHost(urlRaw) + " (connect=" + connectSeconds + "s, read=" + readSeconds + "s, total=" + totalSeconds + "s).");
        int status = connection.getResponseCode();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status);
        }

        long maxMb = plugin.getConfig().getLong(
                "music.url-custom-discs.max-file-size-mb",
                plugin.getConfig().getLong("music.url-custom-discs.max-mp3-size-mb", 12)
        );
        long maxBytes = Math.max(1L, maxMb) * 1024L * 1024L;
        long contentLength = connection.getContentLengthLong();
        String contentType = connection.getContentType();
        musicLog(tokenId, "Server responded HTTP " + status + "; type=" + (contentType == null ? "unknown" : contentType)
                + "; size=" + (contentLength >= 0 ? formatMb(contentLength) : "unknown") + "; limit=" + maxMb + " MB.");

        if (contentLength > maxBytes) {
            throw new IllegalStateException("File is too large (" + formatMb(contentLength) + "). Maximum is " + maxMb + " MB.");
        }

        long downloaded = 0;
        long startedNanos = System.nanoTime();
        int step = Math.max(5, Math.min(100, plugin.getConfig().getInt("music.url-custom-discs.progress-log-percent", 25)));
        int nextPercent = step;

        try (
                InputStream input = connection.getInputStream();
                OutputStream outputStream = Files.newOutputStream(
                        output,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )
        ) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                downloaded += read;

                if (downloaded > maxBytes) {
                    throw new IllegalStateException("File exceeded maximum size during download (limit " + maxMb + " MB).");
                }
                long elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000L;
                if (elapsedSeconds > totalSeconds) {
                    throw new IllegalStateException("Download exceeded total timeout of " + totalSeconds + " seconds.");
                }

                outputStream.write(buffer, 0, read);

                if (consoleMusicLogging() && contentLength > 0) {
                    int percent = (int) Math.min(100L, downloaded * 100L / contentLength);
                    if (percent >= nextPercent) {
                        musicLog(tokenId, "Download " + percent + "% (" + formatMb(downloaded) + " / " + formatMb(contentLength) + ").");
                        while (nextPercent <= percent) nextPercent += step;
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        musicLog(tokenId, "MP3 download finished: " + formatMb(downloaded) + " saved to " + output + ".");
    }

    private boolean isAllowedMp3Url(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }

        int maxLength = plugin.getConfig().getInt(
                "music.url-custom-discs.max-url-length",
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

            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".mp3");
        } catch (Exception ex) {
            return false;
        }
    }

    private String buildDiscNameFromToken(String tokenId) {
        String clean = tokenId.toLowerCase(Locale.ROOT)
                .replace("music-", "")
                .replaceAll("[^a-z0-9_\\-]", "_");

        return "vipdisc_" + clean;
    }

    private String buildFileName(String tokenId, String discName) {
        String cleanToken = tokenId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-]", "_");

        return cleanToken + "_" + discName + "_" + Instant.now().toEpochMilli() + ".mp3";
    }

    private boolean consoleMusicLogging() {
        return plugin.getConfig().getBoolean("music.url-custom-discs.console-logging", true);
    }

    private void musicLog(String tokenId, String message) {
        if (consoleMusicLogging()) plugin.getLogger().info("[Music " + tokenId + "] " + message);
    }

    private void musicWarn(String tokenId, String message) {
        plugin.getLogger().warning("[Music " + tokenId + "] " + message);
    }

    private String safeHost(String raw) {
        try {
            String host = URI.create(raw).getHost();
            return host == null ? "unknown host" : host;
        } catch (Exception ignored) {
            return "unknown host";
        }
    }

    private String formatMb(long bytes) {
        return String.format(Locale.ROOT, "%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private String safeReason(Exception ex) {
        String message = ex.getMessage();

        return message == null || message.isBlank()
                ? ex.getClass().getSimpleName()
                : message;
    }

    private void send(CommandSender sender, String messagePath) {
        sender.sendMessage(plugin.prefix() + Text.color(
                messagesFile.getConfig().getString(messagePath, messagePath)
        ));
    }
}