package com.errsa.vipbridge.mailbox;

import com.errsa.vipbridge.util.ClickableMessages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class MailboxListener implements Listener {

    private final MailboxService mailboxService;

    public MailboxListener(MailboxService mailboxService) {
        this.mailboxService = mailboxService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        int count = mailboxService.countItems(player.getUniqueId());

        if (count > 0) {
            ClickableMessages.sendMailboxReminder(player, count);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MailboxHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.ownerUuid().equals(player.getUniqueId()) && !player.hasPermission("vipbridge.admin")) {
            player.closeInventory();
            return;
        }
        Inventory top = event.getView().getTopInventory();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;
        if (!holder.adminView() && slot == top.getSize() - 1) {
            org.bukkit.Bukkit.getScheduler().runTask(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(MailboxListener.class), () -> {
                player.closeInventory();
                player.performCommand("tokens");
            });
            return;
        }
        // Only ordinary pickup from the top is supported. Never allow shift-click,
        // hotbar swaps, number keys, collect-to-cursor, drops or placement.
        switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME -> {
                ItemStack cursor = event.getCursor();
                if (cursor == null || cursor.getType().isAir()) event.setCancelled(false);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MailboxHolder)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MailboxHolder holder)) {
            return;
        }

        if (event.getPlayer().getUniqueId().equals(holder.ownerUuid()) || event.getPlayer().hasPermission("vipbridge.admin"))
            mailboxService.saveFromInventory(holder.ownerUuid(), holder.ownerName(), event.getInventory());
    }
}