package com.errsa.vipbridge.mailbox;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.util.ClickableMessages;
import com.errsa.vipbridge.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MailboxService {

    private final VIPBridgePlugin plugin;
    private final YamlFile mailboxesFile;

    public MailboxService(VIPBridgePlugin plugin, YamlFile mailboxesFile) {
        this.plugin = plugin;
        this.mailboxesFile = mailboxesFile;
    }

    public void addItem(UUID ownerUuid, String ownerName, ItemStack item, String source) {
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        String base = "players." + ownerUuid;

        mailboxesFile.getConfig().set(base + ".name", ownerName);
        mailboxesFile.getConfig().set(base + ".updated-at", Instant.now().toString());

        // Flush an already-open mailbox before appending, avoiding a stale close overwriting delivery.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Inventory top = viewer.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MailboxHolder holder && holder.ownerUuid().equals(ownerUuid))
                viewer.closeInventory();
        }
        List<ItemStack> items = getItems(ownerUuid);
        items.add(item.clone());

        mailboxesFile.getConfig().set(base + ".items", items);
        mailboxesFile.save();

        Player online = Bukkit.getPlayer(ownerUuid);

        if (online != null && online.isOnline()) {
            ClickableMessages.sendMailboxNewItem(online, items.size());
        }

        plugin.getLogger().info("Added item to item mailbox for " + ownerName + " from " + source + ".");
    }

    public void openMailbox(Player viewer) {
        openMailbox(viewer, viewer.getUniqueId(), viewer.getName(), false);
    }

    public void openMailbox(Player viewer, OfflinePlayer target, boolean adminView) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        openMailbox(viewer, target.getUniqueId(), name, adminView);
    }

    public void openMailbox(Player viewer, UUID ownerUuid, String ownerName, boolean adminView) {
        if (adminView && !viewer.hasPermission("vipbridge.admin")) return;
        if (!adminView && (!ownerUuid.equals(viewer.getUniqueId()) || !viewer.hasPermission("vipbridge.tokens.deliveries"))) return;
        for (Player other : Bukkit.getOnlinePlayers()) {
            Inventory existing = other.getOpenInventory().getTopInventory();
            if (existing.getHolder() instanceof MailboxHolder h && h.ownerUuid().equals(ownerUuid)) other.closeInventory();
        }
        List<ItemStack> items = getItems(ownerUuid);
        int size = plugin.getConfig().getInt("mailbox.size", 27);

        if (size < 9) size = 9;
        if (size > 54) size = 54;
        if (size % 9 != 0) size = 27;
        // Player Deliveries reserves the final slot for a Back button. Grow the GUI
        // when needed so the button never replaces a reward item.
        if (!adminView) {
            int needed = Math.min(54, items.size() + 1);
            int rounded = Math.max(9, ((needed + 8) / 9) * 9);
            size = Math.max(size, rounded);
        }

        String title = adminView
                ? Text.color("&6Deliveries: &e" + ownerName)
                : Text.color("&6Deliveries");

        Inventory inventory = Bukkit.createInventory(
                new MailboxHolder(ownerUuid, ownerName, adminView),
                size,
                title
        );

        int rewardSlots = adminView ? inventory.getSize() : inventory.getSize() - 1;
        int slot = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR && slot < rewardSlots) {
                inventory.setItem(slot++, item);
            }
        }

        if (!adminView) {
            ItemStack back = new ItemStack(Material.ARROW);
            ItemMeta meta = back.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(Text.color("&e&lBack to Token Mailbox"));
                meta.setLore(List.of(Text.color("&7Return to &e/tokens&7.")));
                back.setItemMeta(meta);
            }
            inventory.setItem(inventory.getSize() - 1, back);
        }

        viewer.openInventory(inventory);
    }

    public void saveFromInventory(UUID ownerUuid, String ownerName, Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();

        ItemStack[] contents = inventory.getContents();
        boolean playerView = inventory.getHolder() instanceof MailboxHolder holder && !holder.adminView();
        for (int slot = 0; slot < contents.length; slot++) {
            if (playerView && slot == contents.length - 1) continue; // Back button
            ItemStack item = contents[slot];
            if (item != null && item.getType() != Material.AIR) items.add(item.clone());
        }

        String base = "players." + ownerUuid;

        mailboxesFile.getConfig().set(base + ".name", ownerName);
        mailboxesFile.getConfig().set(base + ".updated-at", Instant.now().toString());
        mailboxesFile.getConfig().set(base + ".items", items);
        mailboxesFile.save();
    }

    public void clear(UUID ownerUuid) {
        String base = "players." + ownerUuid;
        mailboxesFile.getConfig().set(base + ".items", new ArrayList<ItemStack>());
        mailboxesFile.getConfig().set(base + ".updated-at", Instant.now().toString());
        mailboxesFile.save();
    }

    public int countItems(UUID ownerUuid) {
        return getItems(ownerUuid).size();
    }

    @SuppressWarnings("unchecked")
    public List<ItemStack> getItems(UUID ownerUuid) {
        String path = "players." + ownerUuid + ".items";

        List<?> raw = mailboxesFile.getConfig().getList(path);

        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }

        List<ItemStack> items = new ArrayList<>();

        for (Object object : raw) {
            if (object instanceof ItemStack item && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }

        return items;
    }
    public void clearPlayer(UUID ownerUuid) {
        mailboxesFile.getConfig().set("players." + ownerUuid, null);
        mailboxesFile.save();
    }


    public int removeTokenItems(UUID ownerUuid, NamespacedKey tokenKey, String tokenId) {
        List<ItemStack> items = getItems(ownerUuid);

        int before = items.size();

        items.removeIf(item -> {
            if (item == null || !item.hasItemMeta()) {
                return false;
            }

            ItemMeta meta = item.getItemMeta();

            if (meta == null) {
                return false;
            }

            String savedTokenId = meta.getPersistentDataContainer().get(
                    tokenKey,
                    PersistentDataType.STRING
            );

            return tokenId.equalsIgnoreCase(savedTokenId);
        });

        String base = "players." + ownerUuid;

        mailboxesFile.getConfig().set(base + ".items", items);
        mailboxesFile.getConfig().set(base + ".updated-at", Instant.now().toString());
        mailboxesFile.save();

        return before - items.size();
    }
}