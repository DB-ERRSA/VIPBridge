package com.errsa.vipbridge.mailbox;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class MailboxHolder implements InventoryHolder {

    private final UUID ownerUuid;
    private final String ownerName;
    private final boolean adminView;

    public MailboxHolder(UUID ownerUuid, String ownerName, boolean adminView) {
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.adminView = adminView;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean adminView() {
        return adminView;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}