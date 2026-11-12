package com.errsa.vipbridge.tokens;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.util.ClientAccess;
import com.errsa.vipbridge.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class TokenMailboxService {
    private final VIPBridgePlugin plugin;
    private final TokenService tokens;
    private final MailboxService deliveries;

    public TokenMailboxService(VIPBridgePlugin plugin, TokenService tokens, MailboxService deliveries) {
        this.plugin = plugin;
        this.tokens = tokens;
        this.deliveries = deliveries;
    }

    public void open(Player player) {
        if (!ClientAccess.requireTokenGuiSupport(plugin, player)) return;

        Inventory inv = plugin.getServer().createInventory(new TokenMailboxHolder(), 27, Text.color("&6&lToken Mailbox"));
        decorate(inv);

        putType(inv, 10, Material.FILLED_MAP, TokenType.CUSTOM_MAP, "&b&lCustom Map Tokens", player);
        putType(inv, 12, Material.MUSIC_DISC_CAT, TokenType.CUSTOM_MUSIC, "&d&lMusic Disc Tokens", player);

        // Catchphrases are a one-time VIP perk. Non-VIPs never see the entry, and it
        // disappears automatically once the last available catchphrase token is redeemed.
        if (tokens.countAvailableTokens(player.getUniqueId(), TokenType.CATCHPHRASE) > 0) {
            putType(inv, 14, Material.NAME_TAG, TokenType.CATCHPHRASE, "&6&lCatchphrase Token", player);
        }

        putType(inv, 16, Material.PLAYER_HEAD, TokenType.HEADDB, "&a&lHeadDB Tokens", player);

        int deliveryCount = deliveries.countItems(player.getUniqueId());
        ItemStack delivery = item(Material.CHEST, "&6&lDeliveries", List.of(
                "&7Your finished custom rewards live here.",
                "&7Waiting for collection: &e" + deliveryCount,
                "", deliveryCount > 0 ? "&eClick to collect" : "&8Nothing waiting right now"
        ));
        inv.setItem(20, delivery);

        ItemStack shop = item(Material.EMERALD, "&a&lToken Shop", List.of(
                "&7Want another custom reward?",
                "&7Buy additional tokens with server money.",
                "", "&eClick to browse"
        ));
        inv.setItem(24, shop);
        player.openInventory(inv);
    }

    private void putType(Inventory inv, int slot, Material material, TokenType type, String name, Player player) {
        int count = tokens.countAvailableTokens(player.getUniqueId(), type);
        List<String> lore = new ArrayList<>();
        lore.add("&fAvailable: &a" + count);
        lore.add("");
        lore.addAll(description(type));
        lore.add("");
        lore.add(count > 0 ? "&e&lCLICK TO REDEEM" : "&8No tokens available");
        ItemStack item = item(material, name, lore);
        item.setAmount(Math.max(1, Math.min(64, count)));
        inv.setItem(slot, item);
    }

    private List<String> description(TokenType type) {
        String key = switch (type) {
            case CUSTOM_MAP -> "custom-map";
            case CUSTOM_MUSIC -> "custom-music";
            case CATCHPHRASE -> "catchphrase";
            case HEADDB -> "head-db";
        };
        List<String> configured = plugin.getConfig().getStringList("token-mailbox.descriptions." + key);
        if (!configured.isEmpty()) return configured;
        return switch (type) {
            case CUSTOM_MAP -> List.of("&7Turn your favorite image into a", "&7one-of-a-kind map for your builds.");
            case CUSTOM_MUSIC -> List.of("&7Bring a song into the server as", "&7your own custom playable music disc.");
            case CATCHPHRASE -> List.of("&7Give your VIP statue some personality.", "&7Choose the line it says to visitors.");
            case HEADDB -> List.of("&7Pick a decorative head from HeadDB.", "&71 token unlocks 1 head of your choice.");
        };
    }

    private void decorate(Inventory inv) {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            List<String> colored = new ArrayList<>();
            for (String line : lore) colored.add(Text.color(line));
            meta.setLore(colored);
            // Hide vanilla music-disc metadata such as "C418 - cat" without touching our lore.
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            try { meta.addItemFlags(ItemFlag.valueOf("HIDE_JUKEBOX_PLAYABLE")); } catch (IllegalArgumentException ignored) {}
            item.setItemMeta(meta);
        }
        return item;
    }
}
