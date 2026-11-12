package com.errsa.vipbridge.vip;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.UUID;

public final class VipDataService {

    private final VIPBridgePlugin plugin;
    private final YamlFile vipDataFile;

    public VipDataService(VIPBridgePlugin plugin, YamlFile vipDataFile) {
        this.plugin = plugin;
        this.vipDataFile = vipDataFile;
        migrateLegacyOnboardingState();
    }

    private void migrateLegacyOnboardingState() {
        org.bukkit.configuration.ConfigurationSection players = vipDataFile.getConfig().getConfigurationSection("players");
        if (players == null) return;
        boolean changed = false;
        for (String uuid : players.getKeys(false)) {
            String base = "players." + uuid;
            if (!vipDataFile.getConfig().getBoolean(base + ".active", false)) continue;
            if (!vipDataFile.getConfig().contains(base + ".onboarding-pending")
                    && !vipDataFile.getConfig().contains(base + ".onboarding-shown")) {
                vipDataFile.getConfig().set(base + ".onboarding-pending", true);
                vipDataFile.getConfig().set(base + ".onboarding-shown", false);
                changed = true;
            }
        }
        if (changed) vipDataFile.save();
    }

    public boolean hasVipPackage(UUID uuid) {
        return vipDataFile.getConfig().getBoolean("players." + uuid + ".active", false);
    }

    public void recordGrant(OfflinePlayer player, CommandSender sender, boolean forced) {
        UUID uuid = player.getUniqueId();
        String name = player.getName() == null ? uuid.toString() : player.getName();
        String base = "players." + uuid;

        if (!vipDataFile.getConfig().contains(base + ".first-granted-at")) {
            vipDataFile.getConfig().set(base + ".first-granted-at", Instant.now().toString());
        }

        int packageCount = vipDataFile.getConfig().getInt(base + ".package-count", 0);

        vipDataFile.getConfig().set(base + ".name", name);
        vipDataFile.getConfig().set(base + ".active", true);
        vipDataFile.getConfig().set(base + ".last-granted-at", Instant.now().toString());
        vipDataFile.getConfig().set(base + ".last-granted-by", sender.getName());
        vipDataFile.getConfig().set(base + ".last-grant-forced", forced);
        vipDataFile.getConfig().set(base + ".package-count", packageCount + 1);

        // Only the first VIP package starts the first-join onboarding flow.
        // Force grants add rewards again without repeatedly showing the welcome guide.
        if (packageCount == 0) {
            vipDataFile.getConfig().set(base + ".onboarding-pending", true);
            vipDataFile.getConfig().set(base + ".onboarding-shown", false);
        }

        vipDataFile.save();

        plugin.getLogger().info("VIP package recorded for " + name + " forced=" + forced + ".");
    }

    public boolean isActive(UUID uuid) {
        return vipDataFile.getConfig().getBoolean("players." + uuid + ".active", false);
    }

    public boolean isOnboardingPending(UUID uuid) {
        return isActive(uuid) && vipDataFile.getConfig().getBoolean("players." + uuid + ".onboarding-pending", false);
    }

    public void markOnboardingShown(UUID uuid) {
        String base = "players." + uuid;
        vipDataFile.getConfig().set(base + ".onboarding-pending", false);
        vipDataFile.getConfig().set(base + ".onboarding-shown", true);
        vipDataFile.getConfig().set(base + ".onboarding-shown-at", Instant.now().toString());
        vipDataFile.save();
    }

    public int getPackageCount(UUID uuid) {
        return vipDataFile.getConfig().getInt("players." + uuid + ".package-count", 0);
    }

    public void clearPlayer(UUID uuid) {
        vipDataFile.getConfig().set("players." + uuid, null);
        vipDataFile.save();
    }

    public String debugLine(UUID uuid) {
        boolean active = vipDataFile.getConfig().getBoolean("players." + uuid + ".active", false);
        int packageCount = vipDataFile.getConfig().getInt("players." + uuid + ".package-count", 0);

        return "active=" + active + ", package-count=" + packageCount;
    }
}