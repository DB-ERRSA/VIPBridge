package com.errsa.vipbridge.shop;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.tokens.TokenMailboxService;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenType;
import com.errsa.vipbridge.util.ClickableMessages;
import com.errsa.vipbridge.util.ClientAccess;
import com.errsa.vipbridge.util.Text;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;

public final class ShopService implements Listener {
    private final VIPBridgePlugin plugin;
    private final TokenService tokenService;
    private Economy economy;
    private TokenMailboxService tokenMailbox;

    public ShopService(VIPBridgePlugin plugin, TokenService tokenService) { this.plugin = plugin; this.tokenService = tokenService; }
    public void setTokenMailbox(TokenMailboxService tokenMailbox) { this.tokenMailbox = tokenMailbox; }

    public boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) return false;
        economy = provider.getProvider();
        return economy != null;
    }
    public boolean hasEconomy() { return economy != null; }
    public void showShop(Player player) { openShop(player); }

    public void openShop(Player player) {
        if (!player.hasPermission("vipbridge.tokens.shop")) { player.sendMessage(plugin.prefix()+Text.color("&cYou cannot use the Token Shop.")); return; }
        if (!ClientAccess.requireTokenGuiSupport(plugin, player)) return;
        if (!plugin.getConfig().getBoolean("shop.enabled", true)) {
            player.sendMessage(plugin.prefix()+Text.color("&cThe token shop is currently disabled."));
            playFailureSound(player);
            return;
        }
        Inventory inv = plugin.getServer().createInventory(new ShopHolder(), 27, Text.color("&2&lToken Shop"));
        ItemStack pane = make(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i=0;i<27;i++) inv.setItem(i,pane);
        // Public shop: catchphrase is intentionally excluded because it is a one-time VIP reward.
        put(inv,11,Material.FILLED_MAP,TokenType.CUSTOM_MAP,player);
        put(inv,13,Material.MUSIC_DISC_CAT,TokenType.CUSTOM_MUSIC,player);
        put(inv,15,Material.PLAYER_HEAD,TokenType.HEADDB,player);
        inv.setItem(22, make(Material.CHEST,"&e&lToken Mailbox",List.of(
                "&7View tokens you already own.",
                "&7Tokens stay safely stored here.",
                "",
                "&eClick to open"
        )));
        player.openInventory(inv);
    }

    private void put(Inventory inv,int slot,Material mat,TokenType type,Player player) {
        double price=getPrice(type);
        int owned=tokenService.countAvailableTokens(player.getUniqueId(),type);
        List<String> lore=new ArrayList<>();
        lore.add("&fPrice: &a"+money(price));
        if (economy!=null) lore.add("&7Bank balance: &f"+money(economy.getBalance(player)));
        lore.add("");
        lore.addAll(description(type));
        lore.add("");
        lore.add("&7You currently own: &e"+owned);
        lore.add("&7Purchases go straight to /tokens.");
        lore.add("");
        lore.add("&a&lCLICK TO BUY 1");
        inv.setItem(slot,make(mat,displayName(type),lore));
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("vipbridge.tokens.shop")) { p.closeInventory(); return; }
        int slot=event.getRawSlot();
        if(slot==22 && tokenMailbox!=null){tokenMailbox.open(p);return;}
        TokenType type=switch(slot){case 11->TokenType.CUSTOM_MAP;case 13->TokenType.CUSTOM_MUSIC;case 15->TokenType.HEADDB;default->null;};
        if(type!=null){buy(p,type); openShop(p);}
    }
    @EventHandler public void onDrag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof ShopHolder) event.setCancelled(true);}

    /** Performs one token purchase and always gives a PlayerQuests-style transaction receipt. */
    public boolean buy(Player player, TokenType type) {
        if (type == TokenType.CATCHPHRASE) {
            ClickableMessages.sendPurchaseDeclined(player, "Catchphrase Tokens are included with VIP and are not sold in the Token Shop", null, null, null);
            playFailureSound(player);
            return false;
        }
        if (!plugin.getConfig().getBoolean("shop.enabled", true)) {
            ClickableMessages.sendPurchaseDeclined(player, "Token shop is currently disabled", null, null, null);
            playFailureSound(player);
            return false;
        }
        if (economy == null) {
            ClickableMessages.sendPurchaseDeclined(player, "Economy is unavailable — please contact staff", null, null, null);
            playFailureSound(player);
            return false;
        }

        double price=getPrice(type);
        double before=economy.getBalance(player);
        String cleanName=org.bukkit.ChatColor.stripColor(displayName(type));

        if(before<price){
            ClickableMessages.sendPurchaseDeclined(player, "Not enough money", money(before), money(price), money(price-before));
            playFailureSound(player);
            return false;
        }

        EconomyResponse response=economy.withdrawPlayer(player,price);
        if(!response.transactionSuccess()){
            String reason=response.errorMessage==null||response.errorMessage.isBlank()?"Economy transaction failed":response.errorMessage;
            ClickableMessages.sendPurchaseDeclined(player, reason, money(before), money(price), null);
            playFailureSound(player);
            return false;
        }

        double after=economy.getBalance(player);
        int tokensBefore=tokenService.countAvailableTokens(player.getUniqueId(),type);
        tokenService.createTokens(player,type,1,false);
        int tokensAfter=tokenService.countAvailableTokens(player.getUniqueId(),type);

        ClickableMessages.sendPurchaseTransaction(
                player,
                cleanName,
                money(before),
                money(after),
                money(price),
                tokensBefore,
                tokensAfter
        );
        playSuccessSound(player);
        return true;
    }

    public double getPrice(TokenType type) { return switch(type){
        case CUSTOM_MAP->plugin.getConfig().getDouble("shop.prices.custom-map",5000);
        case CUSTOM_MUSIC->plugin.getConfig().getDouble("shop.prices.custom-music",7500);
        case CATCHPHRASE->plugin.getConfig().getDouble("shop.prices.catchphrase",3000);
        case HEADDB->plugin.getConfig().getDouble("shop.prices.head-db",1000);
    };}
    public String displayName(TokenType type){return switch(type){
        case CUSTOM_MAP->Text.color(plugin.getConfig().getString("shop.display.custom-map","&bCustom Map Token"));
        case CUSTOM_MUSIC->Text.color(plugin.getConfig().getString("shop.display.custom-music","&dMusic Disc Token"));
        case CATCHPHRASE->Text.color(plugin.getConfig().getString("shop.display.catchphrase","&6Catchphrase Token"));
        case HEADDB->Text.color(plugin.getConfig().getString("shop.display.head-db","&aHeadDB Token"));
    };}

    private List<String> description(TokenType type) {
        String key = switch (type) {
            case CUSTOM_MAP -> "custom-map";
            case CUSTOM_MUSIC -> "custom-music";
            case CATCHPHRASE -> "catchphrase";
            case HEADDB -> "head-db";
        };
        List<String> configured = plugin.getConfig().getStringList("token-mailbox.descriptions." + key);
        if (!configured.isEmpty()) return configured;
        return List.of("&7A custom reward token.");
    }

    private void playSuccessSound(Player player){playConfiguredSound(player,"sounds.token-purchase-success","ENTITY_EXPERIENCE_ORB_PICKUP",1.0f,1.2f);}
    private void playFailureSound(Player player){playConfiguredSound(player,"sounds.token-purchase-failure","ENTITY_ENDERMAN_TELEPORT",0.7f,0.8f);}
    private void playConfiguredSound(Player player,String path,String fallback,float defaultVolume,float defaultPitch){
        if(!plugin.getConfig().getBoolean(path+".enabled",true)) return;
        String raw=plugin.getConfig().getString(path+".sound",fallback);
        float volume=(float)plugin.getConfig().getDouble(path+".volume",defaultVolume);
        float pitch=(float)plugin.getConfig().getDouble(path+".pitch",defaultPitch);
        try{player.playSound(player.getLocation(),Sound.valueOf(raw.toUpperCase()),volume,pitch);}
        catch(IllegalArgumentException ex){try{player.playSound(player.getLocation(),Sound.valueOf(fallback),defaultVolume,defaultPitch);}catch(IllegalArgumentException ignored){}}
    }

    private ItemStack make(Material mat,String name,List<String> lore){ItemStack i=new ItemStack(mat);ItemMeta m=i.getItemMeta();if(m!=null){m.setDisplayName(Text.color(name));List<String> l=new ArrayList<>();for(String x:lore)l.add(Text.color(x));m.setLore(l);m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);try{m.addItemFlags(ItemFlag.valueOf("HIDE_JUKEBOX_PLAYABLE"));}catch(IllegalArgumentException ignored){}i.setItemMeta(m);}return i;}
    private String money(double value){return economy!=null?economy.format(value):"$"+format(value);}
    private String format(double v){return v==Math.floor(v)?String.format("%,d",(long)v):String.format("%,.2f",v);}
}
