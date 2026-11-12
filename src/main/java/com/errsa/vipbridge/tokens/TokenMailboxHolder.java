package com.errsa.vipbridge.tokens;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record TokenMailboxHolder() implements InventoryHolder {
    @Override public Inventory getInventory() { return null; }
}
