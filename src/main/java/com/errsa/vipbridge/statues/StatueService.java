package com.errsa.vipbridge.statues;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.util.ClientType;
import com.errsa.vipbridge.util.Text;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class StatueService {

    private final VIPBridgePlugin plugin;
    private final YamlFile statuesFile;
    private final YamlFile messagesFile;

    public StatueService(VIPBridgePlugin plugin, YamlFile statuesFile, YamlFile messagesFile) {
        this.plugin = plugin;
        this.statuesFile = statuesFile;
        this.messagesFile = messagesFile;
    }

    public boolean assignStatue(CommandSender sender, OfflinePlayer player) {
        if (!statuesFile.getConfig().getBoolean("enabled", true)) {
            return false;
        }

        ConfigurationSection locations = statuesFile.getConfig().getConfigurationSection("locations");

        if (locations == null) {
            send(sender, "statue-none-open");
            return false;
        }

        String openKey = findOpenLocation();

        if (openKey == null) {
            send(sender, "statue-none-open");
            return false;
        }

        String playerName = player.getName() == null ? player.getUniqueId().toString() : player.getName();
        String npcId = "vip_" + Text.stripForId(playerName);

        Location location = getLocation(openKey);

        if (location == null) {
            sender.sendMessage(plugin.prefix() + Text.color("&cCould not create statue because location &e" + openKey + " &cis invalid."));
            return false;
        }

        boolean created = createNpcWithApi(sender, player, playerName, npcId, location);

        if (!created) {
            return false;
        }

        String base = "locations." + openKey;

        statuesFile.getConfig().set(base + ".taken", true);
        statuesFile.getConfig().set(base + ".owner", playerName);
        statuesFile.getConfig().set(base + ".owner-uuid", player.getUniqueId().toString());
        statuesFile.getConfig().set(base + ".npc-id", npcId);
        statuesFile.getConfig().set(base + ".created", true);

        if (!statuesFile.getConfig().contains(base + ".catchphrase")) {
            statuesFile.getConfig().set(base + ".catchphrase", "");
        }

        statuesFile.save();

        createOrUpdateHologramWithApi(openKey, playerName, npcId, location);

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "statue-assigned",
                "&aAssigned statue."
        ).replace("{location}", openKey).replace("{player}", playerName)));

        return true;
    }

    /** Recreates every assigned statue from statues.yml after plugin startup. */
    public void restoreAssignedStatues() {
        ConfigurationSection locations = statuesFile.getConfig().getConfigurationSection("locations");
        if (locations == null) return;
        int restored = 0;
        for (String key : locations.getKeys(false)) {
            String base = "locations." + key;
            if (!statuesFile.getConfig().getBoolean(base + ".taken", false)) continue;
            String uuidRaw = statuesFile.getConfig().getString(base + ".owner-uuid", "");
            String playerName = statuesFile.getConfig().getString(base + ".owner", "");
            if (uuidRaw == null || uuidRaw.isBlank()) continue;
            try {
                UUID uuid = UUID.fromString(uuidRaw);
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                if (playerName == null || playerName.isBlank()) playerName = player.getName() == null ? uuidRaw : player.getName();
                String npcId = statuesFile.getConfig().getString(base + ".npc-id", "vip_" + Text.stripForId(playerName));
                Location location = getLocation(key);
                if (location == null) continue;
                if (createNpcWithApi(Bukkit.getConsoleSender(), player, playerName, npcId, location)) {
                    createOrUpdateHologramWithApi(key, playerName, npcId, location);
                    statuesFile.getConfig().set(base + ".created", true);
                    restored++;
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid owner UUID for statue " + key + ": " + uuidRaw);
            }
        }
        if (restored > 0) {
            statuesFile.save();
            plugin.getLogger().info("Restored " + restored + " assigned VIP statue(s).");
        }
    }

    /** Staff repair command for one already-assigned statue without re-granting VIP rewards. */
    public boolean rebuildStatue(CommandSender sender, OfflinePlayer player) {
        String key = findLocationByOwner(player.getUniqueId());
        if (key == null) {
            sender.sendMessage(plugin.prefix() + Text.color("&cNo assigned statue was found for that player."));
            return false;
        }
        String base = "locations." + key;
        String playerName = statuesFile.getConfig().getString(base + ".owner", player.getName() == null ? player.getUniqueId().toString() : player.getName());
        String npcId = statuesFile.getConfig().getString(base + ".npc-id", "vip_" + Text.stripForId(playerName));
        Location location = getLocation(key);
        if (location == null) {
            sender.sendMessage(plugin.prefix() + Text.color("&cStatue location &e" + key + " &cis invalid."));
            return false;
        }
        if (!createNpcWithApi(sender, player, playerName, npcId, location)) return false;
        createOrUpdateHologramWithApi(key, playerName, npcId, location);
        statuesFile.getConfig().set(base + ".created", true);
        statuesFile.save();
        sender.sendMessage(plugin.prefix() + Text.color("&aRebuilt statue &e" + key + " &afor &e" + playerName + "&a."));
        return true;
    }

    private boolean createNpcWithApi(CommandSender sender, OfflinePlayer player, String playerName, String npcId, Location location) {
        try {
            Npc existing = FancyNpcsPlugin.get().getNpcManager().getNpc(npcId);

            if (existing != null) {
                existing.removeForAll();
                FancyNpcsPlugin.get().getNpcManager().removeNpc(existing);
            }

            NpcData data = new NpcData(npcId, player.getUniqueId(), location);
            data.setDisplayName("@none");

            ClientType clientType = ClientType.detect(playerName, plugin.getConfig());

            boolean skinApplied = false;

            if (clientType == ClientType.BEDROCK) {
                // Floodgate UUIDs are not Mojang UUIDs, so FancyNpcs cannot resolve the
                // Bedrock player's skin through the Java profile API. An empty fallback (or
                // the old default "Steve") deliberately leaves FancyNpcs' default skin in place.
                String fallbackSkin = statuesFile.getConfig().getString("bedrock-fallback-skin", "");
                if (fallbackSkin != null && !fallbackSkin.isBlank() && !fallbackSkin.equalsIgnoreCase("Steve")) {
                    skinApplied = trySetSkin(data, fallbackSkin, sender, playerName);
                } else {
                    skinApplied = true; // default FancyNPC skin; no failing Mojang lookup
                }
            } else {
                skinApplied = trySetSkin(data, player.getUniqueId().toString(), sender, playerName);

                if (!skinApplied) {
                    skinApplied = trySetSkin(data, playerName, sender, playerName);
                }
            }

            if (!skinApplied) {
                sender.sendMessage(plugin.prefix() + Text.color("&eCould not load skin for &f" + playerName + "&e. Creating statue with default skin."));
            }

            Npc npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);

            FancyNpcsPlugin.get().getNpcManager().registerNpc(npc);

            npc.create();
            npc.spawnForAll();

            // FancyHolograms prevents some NPC modifications after linkwithnpc. Apply
            // these settings synchronously while the NPC is still unlinked.
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc turn_to_player " + npcId + " true");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc displayname " + npcId + " @none");

            return true;
        } catch (Exception ex) {
            sender.sendMessage(plugin.prefix() + Text.color("&cFancyNPC API statue creation failed: &e" + ex.getMessage()));
            plugin.getLogger().warning("FancyNPC API statue creation failed for " + playerName + ": " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    private boolean trySetSkin(NpcData data, String skinIdentifier, CommandSender sender, String playerName) {
        if (skinIdentifier == null || skinIdentifier.isBlank()) {
            return false;
        }

        try {
            data.setSkin(skinIdentifier);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not apply FancyNPC skin '" + skinIdentifier + "' for " + playerName + ": " + ex.getMessage());
            return false;
        }
    }

    public boolean approveCatchphrase(CommandSender sender, UUID ownerUuid, String catchphrase) {
        String locationKey = findLocationByOwner(ownerUuid);

        if (locationKey == null) {
            send(sender, "catchphrase-no-statue");
            return false;
        }

        String base = "locations." + locationKey;
        String playerName = statuesFile.getConfig().getString(base + ".owner", ownerUuid.toString());
        String npcId = statuesFile.getConfig().getString(base + ".npc-id", "");

        if (npcId == null || npcId.isBlank()) {
            npcId = "vip_" + Text.stripForId(playerName);
            statuesFile.getConfig().set(base + ".npc-id", npcId);
        }

        Location location = getLocation(locationKey);

        if (location == null) {
            sender.sendMessage(plugin.prefix() + Text.color("&cCould not update catchphrase because statue location is invalid."));
            return false;
        }

        statuesFile.getConfig().set(base + ".catchphrase", catchphrase);
        statuesFile.save();

        createOrUpdateHologramWithApi(locationKey, playerName, npcId, location);
        applyCatchphraseAction(npcId, playerName, catchphrase);

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "catchphrase-approved",
                "&aApproved catchphrase for &e{player}&a."
        ).replace("{player}", playerName)));

        return true;
    }

    public boolean clearCatchphrase(CommandSender sender, OfflinePlayer target) {
        String locationKey = findLocationByOwner(target.getUniqueId());

        if (locationKey == null) {
            send(sender, "catchphrase-no-statue");
            return false;
        }

        String base = "locations." + locationKey;
        String playerName = statuesFile.getConfig().getString(base + ".owner", target.getName() == null ? target.getUniqueId().toString() : target.getName());
        String npcId = statuesFile.getConfig().getString(base + ".npc-id", "vip_" + Text.stripForId(playerName));

        Location location = getLocation(locationKey);

        if (location == null) {
            sender.sendMessage(plugin.prefix() + Text.color("&cCould not clear catchphrase because statue location is invalid."));
            return false;
        }

        statuesFile.getConfig().set(base + ".catchphrase", "");
        statuesFile.save();

        createOrUpdateHologramWithApi(locationKey, playerName, npcId, location);
        clearCatchphraseAction(npcId);

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "catchphrase-cleared",
                "&aCleared catchphrase for &e{player}&a."
        ).replace("{player}", playerName)));

        return true;
    }

    public void viewCatchphrase(CommandSender sender, OfflinePlayer target) {
        String locationKey = findLocationByOwner(target.getUniqueId());

        if (locationKey == null) {
            send(sender, "catchphrase-no-statue");
            return;
        }

        String base = "locations." + locationKey;
        String playerName = statuesFile.getConfig().getString(base + ".owner", target.getName() == null ? target.getUniqueId().toString() : target.getName());
        String catchphrase = statuesFile.getConfig().getString(base + ".catchphrase", "");

        if (catchphrase == null || catchphrase.isBlank()) {
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "catchphrase-none",
                    "&e{player} &7does not have a catchphrase set."
            ).replace("{player}", playerName)));
            return;
        }

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "catchphrase-view",
                "&6Catchphrase for &e{player}&6: &f{catchphrase}"
        ).replace("{player}", playerName).replace("{catchphrase}", catchphrase)));
    }

    private void createOrUpdateHologramWithApi(String locationKey, String playerName, String npcId, Location npcLocation) {
        if (!statuesFile.getConfig().getBoolean("holograms.enabled", true)) {
            return;
        }

        String safePlayer = Text.stripForId(playerName);

        String hologramId = statuesFile.getConfig()
                .getString("holograms.name-format", "vip_holo_{safe_player}")
                .replace("{safe_player}", safePlayer)
                .replace("{player}", playerName);

        String nameLine = statuesFile.getConfig()
                .getString("holograms.line-format", "&l{player}")
                .replace("{safe_player}", safePlayer)
                .replace("{player}", playerName);

        List<String> lines = new ArrayList<>();
        lines.add(nameLine);

        Location hologramLocation = npcLocation.clone().add(0.0, 2.25, 0.0);

        HologramManager manager = FancyHologramsPlugin.get().getHologramManager();

        manager.getHologram(hologramId).ifPresent(manager::removeHologram);

        TextHologramData data = new TextHologramData(hologramId, hologramLocation);

        data.setText(lines);

        int red = statuesFile.getConfig().getInt("holograms.background.red", 0);
        int green = statuesFile.getConfig().getInt("holograms.background.green", 0);
        int blue = statuesFile.getConfig().getInt("holograms.background.blue", 0);
        int alpha = statuesFile.getConfig().getInt("holograms.background.alpha", 0);

        data.setBackground(Color.fromARGB(alpha, red, green, blue));
        data.setTextShadow(statuesFile.getConfig().getBoolean("holograms.text-shadow", true));
        data.setTextAlignment(TextDisplay.TextAlignment.CENTER);

        String billboard = statuesFile.getConfig().getString("holograms.billboard", "CENTER").toUpperCase();

        try {
            data.setBillboard(Display.Billboard.valueOf(billboard));
        } catch (IllegalArgumentException ex) {
            data.setBillboard(Display.Billboard.CENTER);
        }

        Hologram hologram = manager.create(data);
        manager.addHologram(hologram);

        hologram.forceUpdate();
        hologram.queueUpdate();

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hologram edit " + hologramId + " linkwithnpc " + npcId);

        String base = "locations." + locationKey;
        statuesFile.getConfig().set(base + ".hologram-id", hologramId);
        statuesFile.save();
    }

    private void applyCatchphraseAction(String npcId, String playerName, String catchphrase) {
        String trigger = statuesFile.getConfig().getString("catchphrases.trigger", "ANY_CLICK").toUpperCase();

        String message = statuesFile.getConfig()
                .getString("catchphrases.message-format", "&6{player}&8: &f\"{catchphrase}\"")
                .replace("{player}", playerName)
                .replace("{catchphrase}", catchphrase);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc action " + npcId + " " + trigger + " clear");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc action " + npcId + " " + trigger + " add message " + message);
    }

    private void clearCatchphraseAction(String npcId) {
        String trigger = statuesFile.getConfig().getString("catchphrases.trigger", "ANY_CLICK").toUpperCase();

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "npc action " + npcId + " " + trigger + " clear");
    }

    public boolean removeStatue(CommandSender sender, OfflinePlayer target) {
        String locationKey = findLocationByOwner(target.getUniqueId());

        String playerName = target.getName() == null ? target.getUniqueId().toString() : target.getName();

        if (locationKey == null) {
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "statue-remove-failed",
                    "&cCould not remove statue for &e{player}&c."
            ).replace("{player}", playerName)));
            return false;
        }

        String base = "locations." + locationKey;

        String npcId = statuesFile.getConfig().getString(base + ".npc-id", "");
        String hologramId = statuesFile.getConfig().getString(base + ".hologram-id", "");

        if (npcId != null && !npcId.isBlank()) {
            try {
                Npc npc = FancyNpcsPlugin.get().getNpcManager().getNpc(npcId);

                if (npc != null) {
                    npc.removeForAll();
                    FancyNpcsPlugin.get().getNpcManager().removeNpc(npc);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not remove FancyNPC " + npcId + ": " + ex.getMessage());
            }
        }

        if (hologramId != null && !hologramId.isBlank()) {
            try {
                HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
                manager.getHologram(hologramId).ifPresent(manager::removeHologram);
            } catch (Exception ex) {
                plugin.getLogger().warning("Could not remove FancyHologram " + hologramId + ": " + ex.getMessage());
            }
        }

        statuesFile.getConfig().set(base + ".taken", false);
        statuesFile.getConfig().set(base + ".owner", "");
        statuesFile.getConfig().set(base + ".owner-uuid", "");
        statuesFile.getConfig().set(base + ".npc-id", "");
        statuesFile.getConfig().set(base + ".hologram-id", "");
        statuesFile.getConfig().set(base + ".catchphrase", "");
        statuesFile.getConfig().set(base + ".created", false);
        statuesFile.save();

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "statue-removed",
                "&aRemoved statue for &e{player}&a and freed location &e{location}&a."
        ).replace("{player}", playerName).replace("{location}", locationKey)));

        return true;
    }

    public String getDebugStatus(UUID uuid) {
        String locationKey = findLocationByOwner(uuid);

        if (locationKey == null) {
            return "None";
        }

        String base = "locations." + locationKey;
        boolean created = statuesFile.getConfig().getBoolean(base + ".created", false);
        String npcId = statuesFile.getConfig().getString(base + ".npc-id", "");
        String hologramId = statuesFile.getConfig().getString(base + ".hologram-id", "");

        return locationKey + " created=" + created + " npc=" + npcId + " holo=" + hologramId;
    }

    public void listLocations(CommandSender sender) {
        ConfigurationSection locations = statuesFile.getConfig().getConfigurationSection("locations");

        if (locations == null) {
            send(sender, "statue-none-open");
            return;
        }

        sender.sendMessage(Text.color("&6Statue locations:"));

        for (String key : locations.getKeys(false)) {
            String owner = statuesFile.getConfig().getString("locations." + key + ".owner", "");
            String catchphrase = statuesFile.getConfig().getString("locations." + key + ".catchphrase", "");
            boolean taken = statuesFile.getConfig().getBoolean("locations." + key + ".taken", false);
            boolean created = statuesFile.getConfig().getBoolean("locations." + key + ".created", false);

            String status;

            if (!taken) {
                status = "&aOpen";
            } else if (created) {
                status = "&cCreated for &f" + owner;
            } else {
                status = "&eReserved for &f" + owner;
            }

            if (catchphrase != null && !catchphrase.isBlank()) {
                status += " &7| &f\"" + catchphrase + "\"";
            }

            sender.sendMessage(Text.color("&8- &e" + key + " &7| " + status));
        }
    }

    private String findOpenLocation() {
        ConfigurationSection locations = statuesFile.getConfig().getConfigurationSection("locations");

        if (locations == null) {
            return null;
        }

        for (String key : locations.getKeys(false)) {
            if (!statuesFile.getConfig().getBoolean("locations." + key + ".taken", false)) {
                return key;
            }
        }

        return null;
    }

    private String findLocationByOwner(UUID ownerUuid) {
        ConfigurationSection locations = statuesFile.getConfig().getConfigurationSection("locations");

        if (locations == null) {
            return null;
        }

        for (String key : locations.getKeys(false)) {
            String savedUuid = statuesFile.getConfig().getString("locations." + key + ".owner-uuid", "");

            if (savedUuid.equalsIgnoreCase(ownerUuid.toString())) {
                return key;
            }
        }

        return null;
    }

    private Location getLocation(String key) {
        String base = "locations." + key;

        String worldName = statuesFile.getConfig().getString(base + ".world", "world");
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            return null;
        }

        double x = statuesFile.getConfig().getDouble(base + ".x");
        double y = statuesFile.getConfig().getDouble(base + ".y");
        double z = statuesFile.getConfig().getDouble(base + ".z");

        float yaw = (float) statuesFile.getConfig().getDouble(base + ".yaw");
        float pitch = (float) statuesFile.getConfig().getDouble(base + ".pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

    private void send(CommandSender sender, String messagePath) {
        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(messagePath, messagePath)));
    }
}