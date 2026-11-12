package com.errsa.vipbridge.tokens;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

public final class TokenListener implements Listener {

    private final TokenService tokenService;

    public TokenListener(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() == null) {
            return;
        }

        tokenService.getTokenId(event.getItem()).ifPresent(tokenId -> {
            Player player = event.getPlayer();

            event.setCancelled(true);
            tokenService.redeem(player, tokenId);
        });
    }
}