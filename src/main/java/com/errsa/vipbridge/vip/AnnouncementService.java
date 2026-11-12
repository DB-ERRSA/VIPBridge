package com.errsa.vipbridge.vip;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AnnouncementService {

    private final VIPBridgePlugin plugin;
    private final YamlFile announcementsFile;
    private final YamlFile messagesFile;

    public AnnouncementService(VIPBridgePlugin plugin, YamlFile announcementsFile, YamlFile messagesFile) {
        this.plugin = plugin;
        this.announcementsFile = announcementsFile;
        this.messagesFile = messagesFile;
        migrateLegacyAnnouncements();
    }

    private void migrateLegacyAnnouncements() {
        ConfigurationSection section = announcementsFile.getConfig().getConfigurationSection("announcements");
        if (section == null) return;
        boolean changed = false;
        for (String id : section.getKeys(false)) {
            String base = "announcements." + id;
            // Announcements created by older VIPBridge builds were always broadcast immediately.
            // Mark them delivered so upgrading does not re-announce every historical VIP.
            if (!announcementsFile.getConfig().contains(base + ".announced")) {
                announcementsFile.getConfig().set(base + ".announced", true);
                changed = true;
            }
        }
        if (changed) announcementsFile.save();
    }

    /**
     * Records a VIP announcement. If the new VIP is online it is announced now;
     * otherwise the broadcast + boss bar wait until that player's first join.
     */
    public void announceVip(String playerName, UUID playerUuid) {
        if (!plugin.getConfig().getBoolean("shoutouts.enabled", true)) return;

        String announcementId = "vip-" + System.currentTimeMillis();
        String base = "announcements." + announcementId;
        announcementsFile.getConfig().set(base + ".type", "VIP_GRANT");
        announcementsFile.getConfig().set(base + ".player-name", playerName);
        announcementsFile.getConfig().set(base + ".player-uuid", playerUuid.toString());
        announcementsFile.getConfig().set(base + ".created-at", Instant.now().toString());
        announcementsFile.getConfig().set(base + ".announced", false);
        announcementsFile.save();

        Player vip = Bukkit.getPlayer(playerUuid);
        if (vip != null && vip.isOnline()) {
            deliverAnnouncement(announcementId);
        } else {
            plugin.getLogger().info("VIP announcement for " + playerName + " queued until their next join.");
        }
    }

    /** Announces any VIP grant that was intentionally held while this VIP was offline. */
    public void handleVipJoin(Player player) {
        ConfigurationSection section = announcementsFile.getConfig().getConfigurationSection("announcements");
        if (section == null) return;

        List<String> pending = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            String base = "announcements." + id;
            if (!"VIP_GRANT".equalsIgnoreCase(announcementsFile.getConfig().getString(base + ".type", ""))) continue;
            if (!player.getUniqueId().toString().equalsIgnoreCase(announcementsFile.getConfig().getString(base + ".player-uuid", ""))) continue;
            if (!announcementsFile.getConfig().getBoolean(base + ".announced", false)) pending.add(id);
        }
        // Normally there is only one due to duplicate-grant protection, but this is safe for legacy/forced data.
        for (String id : pending) deliverAnnouncement(id);
    }

    private void deliverAnnouncement(String announcementId) {
        String base = "announcements." + announcementId;
        if (announcementsFile.getConfig().getBoolean(base + ".announced", false)) return;

        String playerName = announcementsFile.getConfig().getString(base + ".player-name", "Unknown");
        String broadcast = plugin.getConfig().getString(
                "shoutouts.broadcast-format",
                "&6&lVIP &8» &e{player} &fhas become a VIP!"
        ).replace("{player}", playerName);
        Bukkit.broadcastMessage(Text.color(broadcast));

        String bossText = plugin.getConfig().getString(
                "shoutouts.bossbar-format",
                "&e{player} &6just became VIP!"
        ).replace("{player}", playerName);

        BossBar bar = Bukkit.createBossBar(Text.color(bossText), BarColor.YELLOW, BarStyle.SOLID);
        String soundName = plugin.getConfig().getString("shoutouts.sound.sound", "UI_TOAST_CHALLENGE_COMPLETE");
        boolean soundEnabled = plugin.getConfig().getBoolean("shoutouts.sound.enabled", true);
        float soundVolume = (float) plugin.getConfig().getDouble("shoutouts.sound.volume", 1.0);
        float soundPitch = (float) plugin.getConfig().getDouble("shoutouts.sound.pitch", 1.0);
        Sound vipSound = null;
        if (soundEnabled) {
            try {
                vipSound = Sound.valueOf(soundName.toUpperCase());
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid shoutouts.sound.sound: " + soundName);
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(online);
            if (vipSound != null) online.playSound(online.getLocation(), vipSound, soundVolume, soundPitch);
            markSeen(online.getUniqueId(), announcementId);
        }

        announcementsFile.getConfig().set(base + ".announced", true);
        announcementsFile.getConfig().set(base + ".announced-at", Instant.now().toString());
        announcementsFile.save();

        int seconds = plugin.getConfig().getInt("shoutouts.bossbar-seconds", 8);
        Bukkit.getScheduler().runTaskLater(plugin, bar::removeAll, Math.max(1, seconds) * 20L);
    }

    public void sendMissedAnnouncements(Player player) {
        List<String> missed = getUnseenVipAnnouncements(player.getUniqueId());
        if (missed.isEmpty()) return;

        List<String> shownNames = new ArrayList<>();
        player.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "missed-shoutouts-header",
                "&6While you were away, these players became VIP:"
        )));

        for (String announcementId : missed) {
            String playerName = announcementsFile.getConfig().getString(
                    "announcements." + announcementId + ".player-name", "Unknown");
            if (!shownNames.contains(playerName.toLowerCase())) {
                player.sendMessage(Text.color(messagesFile.getConfig().getString(
                        "missed-shoutouts-line", "&8- &e{player}"
                ).replace("{player}", playerName)));
                shownNames.add(playerName.toLowerCase());
            }
            markSeen(player.getUniqueId(), announcementId);
        }

        player.sendMessage(Text.color(messagesFile.getConfig().getString(
                "missed-shoutouts-footer", "&7Welcome them when you see them online!")));
        announcementsFile.save();
    }

    private List<String> getUnseenVipAnnouncements(UUID viewerUuid) {
        List<String> missed = new ArrayList<>();
        ConfigurationSection section = announcementsFile.getConfig().getConfigurationSection("announcements");
        if (section == null) return missed;

        for (String announcementId : section.getKeys(false)) {
            String base = "announcements." + announcementId;
            if (!"VIP_GRANT".equalsIgnoreCase(announcementsFile.getConfig().getString(base + ".type", ""))) continue;
            // Do not tell people about an offline VIP until that VIP has actually joined and been announced.
            if (!announcementsFile.getConfig().getBoolean(base + ".announced", false)) continue;

            String vipUuid = announcementsFile.getConfig().getString(base + ".player-uuid", "");
            if (vipUuid.equalsIgnoreCase(viewerUuid.toString())) {
                markSeen(viewerUuid, announcementId);
                continue;
            }
            if (!hasSeen(viewerUuid, announcementId)) missed.add(announcementId);
        }
        return missed;
    }

    private boolean hasSeen(UUID viewerUuid, String announcementId) {
        return announcementsFile.getConfig().getBoolean("seen." + viewerUuid + "." + announcementId, false);
    }

    public void markSeen(UUID viewerUuid, String announcementId) {
        announcementsFile.getConfig().set("seen." + viewerUuid + "." + announcementId, true);
    }
}
