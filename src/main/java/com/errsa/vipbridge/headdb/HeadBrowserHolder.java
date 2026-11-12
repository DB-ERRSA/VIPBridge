package com.errsa.vipbridge.headdb;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.List;
public record HeadBrowserHolder(int page, List<Integer> headIds) implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
