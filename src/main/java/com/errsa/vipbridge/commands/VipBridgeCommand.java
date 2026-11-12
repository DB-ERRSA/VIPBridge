package com.errsa.vipbridge.commands;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.maps.MapService;
import com.errsa.vipbridge.headdb.HeadDBService;
import com.errsa.vipbridge.music.MusicService;
import com.errsa.vipbridge.shop.ShopService;
import com.errsa.vipbridge.statues.StatueService;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenMailboxService;
import com.errsa.vipbridge.tokens.TokenType;
import com.errsa.vipbridge.util.Text;
import com.errsa.vipbridge.vip.AnnouncementService;
import com.errsa.vipbridge.vip.VipDataService;
import com.errsa.vipbridge.vip.VipOnboardingService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class VipBridgeCommand implements CommandExecutor, TabCompleter {

    private final ShopService shopService;
    private final VIPBridgePlugin plugin;
    private final TokenService tokenService;
    private final StatueService statueService;
    private final MailboxService mailboxService;
    private final YamlFile messagesFile;
    private final VipDataService vipDataService;
    private final AnnouncementService announcementService;
    private final MusicService musicService;
    private final MapService mapService;
    private final TokenMailboxService tokenMailboxService;
    private final HeadDBService headDBService;
    private final VipOnboardingService onboardingService;

    public VipBridgeCommand(
            VIPBridgePlugin plugin,
            TokenService tokenService,
            StatueService statueService,
            MailboxService mailboxService,
            ShopService shopService,
            VipDataService vipDataService,
            AnnouncementService announcementService,
            MusicService musicService,
            MapService mapService,
            TokenMailboxService tokenMailboxService,
            HeadDBService headDBService,
            VipOnboardingService onboardingService,
            YamlFile messagesFile
    ) {
        this.plugin = plugin;
        this.tokenService = tokenService;
        this.statueService = statueService;
        this.mailboxService = mailboxService;
        this.shopService = shopService;
        this.vipDataService = vipDataService;
        this.announcementService = announcementService;
        this.musicService = musicService;
        this.mapService = mapService;
        this.tokenMailboxService = tokenMailboxService;
        this.headDBService = headDBService;
        this.onboardingService = onboardingService;
        this.messagesFile = messagesFile;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                onboardingService.sendGuide(player);
            } else {
                sendHelp(sender);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload" -> {
                if (!admin(sender)) {
                    return true;
                }

                plugin.reloadAll();
                msg(sender, "reloaded");
                return true;
            }

            case "grant" -> {
                if (!admin(sender)) {
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(Text.color("&cUsage: /" + label + " grant <player>"));
                    return true;
                }

                grant(sender, args[1], Arrays.asList(args).contains("--force"));
                return true;
            }

            case "guide" -> {
                if (!(sender instanceof Player player)) { msg(sender, "player-only"); return true; }
                onboardingService.sendGuide(player);
                return true;
            }

            case "token" -> {
                if (!admin(sender)) {
                    return true;
                }

                return handleTokenAdmin(sender, label, args);
            }

            case "statue" -> {
                if (!admin(sender)) {
                    return true;
                }

                if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                    statueService.removeStatue(sender, target);
                    return true;
                }
                if (args.length >= 3 && args[1].equalsIgnoreCase("rebuild")) {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
                    statueService.rebuildStatue(sender, target);
                    return true;
                }

                statueService.listLocations(sender);
                return true;
            }

            case "mailbox" -> {
                if (!admin(sender)) return true;
                return handleMailbox(sender, label, args);
            }

            case "catchphrase" -> {
                if (!admin(sender)) {
                    return true;
                }

                return handleCatchphrase(sender, label, args);
            }

            case "debug" -> {
                if (!admin(sender)) {
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(Text.color("&cUsage: /" + label + " debug <player>"));
                    return true;
                }

                debugPlayer(sender, args[1]);
                return true;
            }

            case "player" -> {
                if (!admin(sender)) {
                    return true;
                }

                return handlePlayerAdmin(sender, label, args);
            }

            default -> {
                msg(sender, "unknown-command");
                return true;
            }


        }
    }


    private TokenType tokenTypeFromShopInput(String input) {
        if (input == null) {
            return null;
        }

        return switch (input.toLowerCase()) {
            case "map", "custommap", "custom-map" -> TokenType.CUSTOM_MAP;
            case "music", "custommusic", "custom-music" -> TokenType.CUSTOM_MUSIC;
            case "head", "heads", "headdb", "hdb" -> TokenType.HEADDB;
            default -> null;
        };
    }

    private void grant(CommandSender sender, String playerName, boolean force) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        String targetName = target.getName() == null ? playerName : target.getName();

        boolean preventDuplicates = plugin.getConfig().getBoolean("vip.prevent-duplicate-grants", true);

        if (preventDuplicates && vipDataService.hasVipPackage(target.getUniqueId()) && !force) {
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "vip-already-granted",
                    "&c{player} already has a VIP package. Use &e/vip grant {player} --force &cto grant again."
            ).replace("{player}", targetName)));
            return;
        }

        if (force && vipDataService.hasVipPackage(target.getUniqueId())) {
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "vip-force-grant",
                    "&eForce-granting another VIP package to &f{player}&e."
            ).replace("{player}", targetName)));
        }

        String lpCommand = plugin.getConfig().getString("vip.luckperms-command", "lp user {player} promote main")
                .replace("{player}", targetName);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), lpCommand);

        int mapAmount = plugin.getConfig().getInt("vip.rewards.custom-map-tokens", 2);
        int musicAmount = plugin.getConfig().getInt("vip.rewards.custom-music-tokens", 2);
        int headAmount = plugin.getConfig().getInt("vip.rewards.head-db-tokens", 1);
        int catchAmount = plugin.getConfig().getInt("vip.rewards.catchphrase-tokens", 1);

        if (mapAmount > 0) {
            tokenService.createTokens(target, TokenType.CUSTOM_MAP, mapAmount);
        }

        if (musicAmount > 0) {
            tokenService.createTokens(target, TokenType.CUSTOM_MUSIC, musicAmount);
        }

        if (headAmount > 0) {
            tokenService.createTokens(target, TokenType.HEADDB, headAmount);
        }

        if (catchAmount > 0) {
            tokenService.createTokens(target, TokenType.CATCHPHRASE, catchAmount);
        }

        if (plugin.getConfig().getBoolean("vip.rewards.statue", true)) {
            statueService.assignStatue(sender, target);
        }

        vipDataService.recordGrant(target, sender, force);

        if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "grant-received",
                    "&6You received your VIP package!"
            )));

        }

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "grant-success",
                "&aGranted VIP package to &e{player}&a."
        ).replace("{player}", targetName)));

        announcementService.announceVip(targetName, target.getUniqueId());
    }

    private void debugPlayer(CommandSender sender, String playerName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        String targetName = target.getName() == null ? playerName : target.getName();

        int mailboxCount = mailboxService.countItems(target.getUniqueId());
        int tokenCount = tokenService.countTokens(target.getUniqueId());
        int packageCount = vipDataService.getPackageCount(target.getUniqueId());
        String statueStatus = statueService.getDebugStatus(target.getUniqueId());

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "debug-header",
                "&6VIP debug for &e{player}&6:"
        ).replace("{player}", targetName)));

        sender.sendMessage(Text.color(messagesFile.getConfig().getString(
                "debug-vip",
                "&8- &fVIP package count: &e{count}"
        ).replace("{count}", String.valueOf(packageCount))));

        sender.sendMessage(Text.color(messagesFile.getConfig().getString(
                "debug-mailbox",
                "&8- &fMailbox items: &e{count}"
        ).replace("{count}", String.valueOf(mailboxCount))));

        sender.sendMessage(Text.color(messagesFile.getConfig().getString(
                "debug-statue",
                "&8- &fStatue: &e{status}"
        ).replace("{status}", statueStatus)));

        sender.sendMessage(Text.color("&8- &fToken count: &e" + tokenCount));

        tokenService.listTokens(sender, target);
    }

    private boolean handlePlayerAdmin(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.color("&cUsage: /" + label + " player reset <player> --confirm"));
            return true;
        }

        String action = args[1].toLowerCase();

        if (!action.equals("reset")) {
            sender.sendMessage(Text.color("&cUnknown player action. Use reset."));
            return true;
        }

        String playerName = args[2];

        if (!Arrays.asList(args).contains("--confirm")) {
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "player-reset-confirm",
                    "&cThis is destructive. Use &e/vip player reset {player} --confirm &cto confirm."
            ).replace("{player}", playerName)));
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        String targetName = target.getName() == null ? playerName : target.getName();

        runVipResetCommands(targetName);

        statueService.removeStatue(sender, target);
        tokenService.clearPlayerTokens(target.getUniqueId());
        mailboxService.clearPlayer(target.getUniqueId());
        vipDataService.clearPlayer(target.getUniqueId());

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "player-reset-done",
                "&aFully reset VIPBridge data for &e{player}&a."
        ).replace("{player}", targetName)));

        return true;
    }

    private void runVipResetCommands(String playerName) {
        if (!plugin.getConfig().getBoolean("vip.reset-commands.enabled", true)) {
            return;
        }

        for (String rawCommand : plugin.getConfig().getStringList("vip.reset-commands.commands")) {
            if (rawCommand == null || rawCommand.isBlank()) {
                continue;
            }

            String command = rawCommand.replace("{player}", playerName);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private boolean handleTokenAdmin(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color("&cUsage: /" + label + " token approve <tokenId> <url|catchphrase>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " token deny <tokenId> <reason>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " token replace <tokenId>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " token give <player> <map|music|catchphrase|head> [amount]"));
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("give")) {
            if (args.length < 4) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " token give <player> <map|music|catchphrase|head> [amount]"));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            TokenType type = tokenTypeFromShopInput(args[3]);

            if (type == null) {
                sender.sendMessage(plugin.prefix() + Text.color("&cUnknown token type. Use: &emap&c, &emusic&c, or &ehead&c."));
                return true;
            }

            int amount = 1;

            if (args.length >= 5) {
                try {
                    amount = Integer.parseInt(args[4]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(plugin.prefix() + Text.color("&cAmount must be a number."));
                    return true;
                }
            }

            if (amount < 1 || amount > 64) {
                sender.sendMessage(plugin.prefix() + Text.color("&cAmount must be between 1 and 64."));
                return true;
            }

            tokenService.createTokens(target, type, amount);

            String targetName = target.getName() == null ? args[2] : target.getName();

            sender.sendMessage(plugin.prefix() + Text.color("&aGave &e" + amount + " &a" + type.name() + " token(s) to &e" + targetName + "&a."));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(Text.color("&cUsage: /" + label + " token approve <tokenId> <url|catchphrase>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " token deny <tokenId> <reason>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " token replace <tokenId>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " token give <player> <map|music|catchphrase|head> [amount]"));
            return true;
        }

        String tokenId = args[2];

        if (action.equals("approve")) {
            String value = args.length >= 4
                    ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                    : "";

            TokenType tokenType = tokenService.getTokenType(tokenId);

            if (tokenType == null) {
                tokenService.approve(sender, tokenId, value);
                return true;
            }

            if (tokenType == TokenType.CATCHPHRASE) {
                UUID ownerUuid = tokenService.approveCatchphraseToken(sender, tokenId, value);

                if (ownerUuid != null) {
                    statueService.approveCatchphrase(sender, ownerUuid, value);
                }

                return true;
            }

            if (tokenType == TokenType.CUSTOM_MUSIC) {
                musicService.approveToken(sender, tokenId, value);
                return true;
            }

            if (tokenType == TokenType.CUSTOM_MAP) {
                mapService.approveToken(sender, tokenId, value);
                return true;
            }

            tokenService.approve(sender, tokenId, value);
            return true;
        }

        if (action.equals("deny")) {
            String reason = args.length >= 4
                    ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                    : "";

            tokenService.deny(sender, tokenId, reason);
            return true;
        }

        if (action.equals("replace")) {
            tokenService.replaceToken(sender, tokenId);
            return true;
        }

        sender.sendMessage(Text.color("&cUnknown token action. Use approve, deny, replace, or give."));
        return true;
    }

    private boolean handleCatchphrase(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color("&cUsage: /" + label + " catchphrase approve <tokenId> <catchphrase>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " catchphrase view <player>"));
            sender.sendMessage(Text.color("&cUsage: /" + label + " catchphrase clear <player>"));
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("approve")) {
            if (args.length < 4) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " catchphrase approve <tokenId> <catchphrase>"));
                return true;
            }

            String tokenId = args[2];
            String catchphrase = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

            UUID ownerUuid = tokenService.approveCatchphraseToken(sender, tokenId, catchphrase);

            if (ownerUuid == null) {
                return true;
            }

            statueService.approveCatchphrase(sender, ownerUuid, catchphrase);
            return true;
        }

        if (action.equals("view")) {
            if (args.length < 3) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " catchphrase view <player>"));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            statueService.viewCatchphrase(sender, target);
            return true;
        }

        if (action.equals("clear")) {
            if (args.length < 3) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " catchphrase clear <player>"));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            statueService.clearCatchphrase(sender, target);
            return true;
        }

        sender.sendMessage(Text.color("&cUnknown catchphrase action. Use approve, view, or clear."));
        return true;
    }

    private boolean handleMailbox(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox add|view|clear <player>"));
            return true;
        }

        String action = args[1].toLowerCase();

        if (action.equals("add")) {
            if (!(sender instanceof Player staff)) {
                msg(sender, "player-only");
                return true;
            }

            if (args.length < 3) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox add <player>"));
                return true;
            }

            ItemStack hand = staff.getInventory().getItemInMainHand();

            if (hand == null || hand.getType() == Material.AIR) {
                sender.sendMessage(plugin.prefix() + Text.color("&cHold the item you want to add to the player's mailbox."));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            String targetName = target.getName() == null ? args[2] : target.getName();

            mailboxService.addItem(target.getUniqueId(), targetName, hand.clone(), "staff:" + staff.getName());

            staff.getInventory().setItemInMainHand(null);
            staff.sendMessage(plugin.prefix() + Text.color("&aAdded held item to &e" + targetName + "&a's item mailbox."));
            return true;
        }

        if (action.equals("view")) {
            if (!(sender instanceof Player staff)) {
                msg(sender, "player-only");
                return true;
            }

            if (args.length < 3) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox view <player>"));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            mailboxService.openMailbox(staff, target, true);
            return true;
        }

        if (action.equals("clear")) {
            if (args.length < 3) {
                sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox clear <player>"));
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            mailboxService.clear(target.getUniqueId());

            String targetName = target.getName() == null ? args[2] : target.getName();
            sender.sendMessage(plugin.prefix() + Text.color("&aCleared item mailbox for &e" + targetName + "&a."));
            return true;
        }

        sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox"));
        sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox add <player>"));
        sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox view <player>"));
        sender.sendMessage(Text.color("&cUsage: /" + label + " mailbox clear <player>"));
        return true;
    }



    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("vipbridge.admin")) {
            return true;
        }

        msg(sender, "no-permission");
        return false;
    }

    private void msg(CommandSender sender, String path) {
        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(path, path)));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.color("&6&lVIP &8• &fCommands"));
        sender.sendMessage(Text.color("&e/vip &7- VIP guide"));
        sender.sendMessage(Text.color("&e/tokens &7- Tokens, redemptions, and deliveries"));
        sender.sendMessage(Text.color("&e/tokenshop &7- Buy tokens"));

        if (sender.hasPermission("vipbridge.admin")) {
            sender.sendMessage(Text.color("&8Staff:"));
            sender.sendMessage(Text.color("&e/vip grant <player> [--force]"));
            sender.sendMessage(Text.color("&e/vip token give|approve|deny|replace ..."));
            sender.sendMessage(Text.color("&e/vip statue remove|rebuild <player>"));
            sender.sendMessage(Text.color("&e/vip mailbox add|view|clear <player>"));
            sender.sendMessage(Text.color("&e/vip player reset <player> --confirm"));
            sender.sendMessage(Text.color("&e/vip debug <player> &8• &e/vip reload"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("guide"));

            if (sender.hasPermission("vipbridge.admin")) {
                base.addAll(List.of("grant", "token", "statue", "mailbox", "catchphrase", "debug", "player", "reload"));
            }

            return filter(base, args[0]);
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("mailbox") && sender.hasPermission("vipbridge.admin")) {
            return filter(List.of("add", "view", "clear"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("catchphrase") && sender.hasPermission("vipbridge.admin")) {
            return filter(List.of("approve", "view", "clear"), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("token") && sender.hasPermission("vipbridge.admin")) {
            return filter(List.of("approve", "deny", "replace", "give"), args[1]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("token") && args[1].equalsIgnoreCase("give") && sender.hasPermission("vipbridge.admin")) {
            return filter(List.of("map", "music", "catchphrase", "head"), args[3]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("statue") && sender.hasPermission("vipbridge.admin")) {
            return filter(List.of("remove", "rebuild"), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("player") && sender.hasPermission("vipbridge.admin")) {
            return filter(List.of("reset"), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase();

        return options.stream()
                .filter(option -> option.toLowerCase().startsWith(lower))
                .toList();
    }
}