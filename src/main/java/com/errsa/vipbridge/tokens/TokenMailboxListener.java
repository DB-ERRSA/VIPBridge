package com.errsa.vipbridge.tokens;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.headdb.HeadDBService;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.shop.ShopService;
import com.errsa.vipbridge.util.ClickableMessages;
import com.errsa.vipbridge.util.ClientAccess;
import com.errsa.vipbridge.vip.VipOnboardingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class TokenMailboxListener implements Listener {
    private final VIPBridgePlugin plugin;
    private final TokenService tokens;
    private final TokenMailboxService mailbox;
    private final ShopService shop;
    private final HeadDBService heads;
    private final MailboxService deliveries;
    private final VipOnboardingService onboarding;

    public TokenMailboxListener(VIPBridgePlugin plugin, TokenService tokens, TokenMailboxService mailbox, ShopService shop, HeadDBService heads, MailboxService deliveries, VipOnboardingService onboarding) {
        this.plugin = plugin;
        this.tokens = tokens;
        this.mailbox = mailbox;
        this.shop = shop;
        this.heads = heads;
        this.deliveries = deliveries;
        this.onboarding = onboarding;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tokens.migrateLegacyPhysicalTokens(player);
        boolean firstVipJoin = onboarding.handleJoin(player);
        int count = tokens.countAvailableTokens(player.getUniqueId());
        if (!firstVipJoin && count > 0 && tokens.shouldSendDailyReminder(player.getUniqueId())) {
            if (ClientAccess.tokenGuisSupported(plugin, player)) {
                ClickableMessages.sendTokenReminder(player, count,
                        tokens.countAvailableTokens(player.getUniqueId(), TokenType.CUSTOM_MAP),
                        tokens.countAvailableTokens(player.getUniqueId(), TokenType.CUSTOM_MUSIC),
                        tokens.countAvailableTokens(player.getUniqueId(), TokenType.CATCHPHRASE),
                        tokens.countAvailableTokens(player.getUniqueId(), TokenType.HEADDB));
            } else {
                ClientAccess.sendJavaOnlyNotice(plugin, player);
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TokenMailboxHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;

        TokenType type = switch (event.getRawSlot()) {
            case 10 -> TokenType.CUSTOM_MAP;
            case 12 -> TokenType.CUSTOM_MUSIC;
            case 14 -> TokenType.CATCHPHRASE;
            case 16 -> TokenType.HEADDB;
            default -> null;
        };

        if (event.getRawSlot() == 20) { if (player.hasPermission("vipbridge.tokens.deliveries")) deliveries.openMailbox(player); return; }
        if (event.getRawSlot() == 24) { if (player.hasPermission("vipbridge.tokens.shop")) shop.openShop(player); return; }
        if (type == null) return;

        String id = tokens.findFirstAvailableToken(player.getUniqueId(), type);
        if (id == null) {
            player.sendMessage("§cYou do not have any available " + type.displayName() + " tokens.");
            return;
        }
        if (type == TokenType.HEADDB) { if (player.hasPermission("vipbridge.heads.search")) heads.open(player, 0); }
        else tokens.redeem(player, id);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TokenMailboxHolder) event.setCancelled(true);
    }
}
