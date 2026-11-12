package com.errsa.vipbridge.vip;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenType;
import com.errsa.vipbridge.util.ClickableMessages;
import com.errsa.vipbridge.util.ClientAccess;
import com.errsa.vipbridge.util.Text;
import org.bukkit.entity.Player;

import java.util.List;

/** First-join VIP welcome + reusable short guide. */
public final class VipOnboardingService {
    private final VIPBridgePlugin plugin;
    private final VipDataService vipData;
    private final TokenService tokens;

    public VipOnboardingService(VIPBridgePlugin plugin, VipDataService vipData, TokenService tokens) {
        this.plugin = plugin;
        this.vipData = vipData;
        this.tokens = tokens;
    }

    /** @return true when the first-time onboarding was shown on this join. */
    public boolean handleJoin(Player player) {
        if (!plugin.getConfig().getBoolean("onboarding.enabled", true)) return false;
        if (!vipData.isOnboardingPending(player.getUniqueId())) return false;

        // Delay slightly so the join/MOTD messages do not bury the welcome package.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !vipData.isOnboardingPending(player.getUniqueId())) return;
            sendWelcome(player);
            vipData.markOnboardingShown(player.getUniqueId());
        }, 30L);
        return true;
    }

    public void sendWelcome(Player player) {
        int maps = tokens.countAvailableTokens(player.getUniqueId(), TokenType.CUSTOM_MAP);
        int music = tokens.countAvailableTokens(player.getUniqueId(), TokenType.CUSTOM_MUSIC);
        int catchphrases = tokens.countAvailableTokens(player.getUniqueId(), TokenType.CATCHPHRASE);
        int heads = tokens.countAvailableTokens(player.getUniqueId(), TokenType.HEADDB);
        if (ClientAccess.tokenGuisSupported(plugin, player)) {
            ClickableMessages.sendVipWelcome(player, maps, music, catchphrases, heads);
        } else {
            int total = maps + music + catchphrases + heads;
            player.sendMessage(plugin.prefix() + Text.color("&6Welcome to VIP! &fYour reward package is safely stored."));
            player.sendMessage(Text.color("&7Tokens waiting: &e" + total + " &8(Maps " + maps + " • Music " + music + " • Catchphrase " + catchphrases + " • Heads " + heads + ")"));
            ClientAccess.sendJavaOnlyNotice(plugin, player);
        }
        sendGuide(player);
    }

    public void sendGuide(Player player) {
        if (!plugin.getConfig().getBoolean("onboarding.enabled", true)) return;
        String title = plugin.getConfig().getString("onboarding.title", "&6&lVIP QUICK GUIDE");
        player.sendMessage(Text.color("&8&m---------------- &r" + title + " &8&m----------------"));
        List<String> lines;
        if (!ClientAccess.tokenGuisSupported(plugin, player)) {
            lines = plugin.getConfig().getStringList("onboarding.bedrock-lines");
            if (lines.isEmpty()) {
                lines = List.of(
                        "&eCustom reward tokens are currently redeemable on Java Edition only.",
                        "&7Your VIP tokens stay safely saved until you join using Java."
                );
            }
        } else {
            lines = plugin.getConfig().getStringList("onboarding.lines");
            if (lines.isEmpty()) {
                lines = List.of(
                        "&e/tokens &7- View tokens, redeem rewards, and collect deliveries.",
                        "&e/tokenshop &7- Buy additional tokens with server money."
                );
            }
        }
        for (String line : lines) player.sendMessage(Text.color(line));
        String footer = plugin.getConfig().getString("onboarding.footer", "&7Run &e/vip &7to see this again.");
        if (footer != null && !footer.isBlank()) player.sendMessage(Text.color(footer));
        player.sendMessage(Text.color("&8&m------------------------------------------------"));
    }
}
