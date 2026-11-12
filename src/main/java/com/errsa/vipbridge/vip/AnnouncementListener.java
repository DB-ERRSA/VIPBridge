package com.errsa.vipbridge.vip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class AnnouncementListener implements Listener {

    private final AnnouncementService announcementService;

    public AnnouncementListener(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // If this player was granted VIP while offline, this is the moment the
        // server-wide chat + boss bar should happen.
        announcementService.handleVipJoin(event.getPlayer());
        announcementService.sendMissedAnnouncements(event.getPlayer());
    }
}
