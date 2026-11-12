package com.errsa.vipbridge;

import com.errsa.vipbridge.commands.VipBridgeCommand;
import com.errsa.vipbridge.api.VIPBridgeAPI;
import com.errsa.vipbridge.headdb.HeadDBService;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.mailbox.MailboxListener;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.maps.MapService;
import com.errsa.vipbridge.music.MusicService;
import com.errsa.vipbridge.shop.ShopService;
import com.errsa.vipbridge.statues.StatueService;
import com.errsa.vipbridge.tokens.TokenListener;
import com.errsa.vipbridge.tokens.TokenMailboxListener;
import com.errsa.vipbridge.tokens.TokenMailboxService;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.util.Text;
import com.errsa.vipbridge.vip.AnnouncementListener;
import com.errsa.vipbridge.vip.AnnouncementService;
import com.errsa.vipbridge.vip.VipDataService;
import com.errsa.vipbridge.vip.VipOnboardingService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class VIPBridgePlugin extends JavaPlugin {

    private YamlFile tokensFile;
    private YamlFile messagesFile;
    private YamlFile statuesFile;
    private YamlFile mailboxesFile;
    private YamlFile vipDataFile;
    private YamlFile announcementsFile;

    private MusicService musicService;
    private VipDataService vipDataService;
    private AnnouncementService announcementService;
    private VipOnboardingService vipOnboardingService;
    private TokenService tokenService;
    private StatueService statueService;
    private MailboxService mailboxService;
    private MapService mapService;
    private ShopService shopService;
    private TokenMailboxService tokenMailboxService;
    private HeadDBService headDBService;
    private VIPBridgeAPI api;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("tokens.yml");
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("statues.yml");
        saveResourceIfMissing("mailboxes.yml");
        saveResourceIfMissing("vipdata.yml");
        saveResourceIfMissing("announcements.yml");

        this.tokensFile = new YamlFile(this, "tokens.yml");
        this.messagesFile = new YamlFile(this, "messages.yml");
        this.statuesFile = new YamlFile(this, "statues.yml");
        this.mailboxesFile = new YamlFile(this, "mailboxes.yml");
        this.vipDataFile = new YamlFile(this, "vipdata.yml");
        this.announcementsFile = new YamlFile(this, "announcements.yml");
        migrateConfigDefaults();
        normalizeGenericMessages();

        this.mailboxService = new MailboxService(this, mailboxesFile);
        this.statueService = new StatueService(this, statuesFile, messagesFile);
        this.tokenService = new TokenService(this, tokensFile, messagesFile, mailboxService);
        if (getConfig().getBoolean("music.url-custom-discs.reset-processing-on-startup", true)) {
            int recovered = tokenService.resetOrphanedMusicProcessingTokens();
            if (recovered > 0) getLogger().warning("Recovered " + recovered + " music token(s) left in PROCESSING by a previous shutdown; reset to UNUSED.");
        }
        this.musicService = new MusicService(this, messagesFile, tokenService, mailboxService);
        this.mapService = new MapService(this, messagesFile, tokenService, mailboxService);
        this.shopService = new ShopService(this, tokenService);
        this.tokenMailboxService = new TokenMailboxService(this, tokenService, mailboxService);
        this.shopService.setTokenMailbox(tokenMailboxService);
        this.headDBService = new HeadDBService(this, tokenService, mailboxService);
        this.api = new VIPBridgeAPI(tokenService);
        getServer().getServicesManager().register(VIPBridgeAPI.class, api, this, ServicePriority.Normal);
        this.vipDataService = new VipDataService(this, vipDataFile);
        this.announcementService = new AnnouncementService(this, announcementsFile, messagesFile);
        this.vipOnboardingService = new VipOnboardingService(this, vipDataService, tokenService);

        boolean headDBHooked = headDBService.hook();
        if (headDBHooked) {
            getLogger().info("HeadDB hooked successfully. HeadDB tokens are enabled.");
        } else {
            getLogger().warning("HeadDB was not found. HeadDB token browsing will be unavailable.");
        }

        if (shopService.setupEconomy()) {
            getLogger().info("Vault economy hooked successfully.");
        } else {
            getLogger().warning("Vault economy was not found. /tokenshop will be unavailable.");
        }

        PluginCommand command = getCommand("vip");

        if (command != null) {
            VipBridgeCommand executor = new VipBridgeCommand(
                    this,
                    tokenService,
                    statueService,
                    mailboxService,
                    shopService,
                    vipDataService,
                    announcementService,
                    musicService,
                    mapService,
                    tokenMailboxService,
                    headDBService,
                    vipOnboardingService,
                    messagesFile
            );

            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Simple public commands: /tokens opens the mailbox; /tokenshop opens the shop.
        PluginCommand tokenShopCommand = getCommand("tokenshop");
        if (tokenShopCommand != null) {
            tokenShopCommand.setExecutor((sender, cmd, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(prefix() + Text.color("&cOnly players can open the Token Shop."));
                    return true;
                }
                if (player.hasPermission("vipbridge.tokens.shop")) shopService.openShop(player);
                return true;
            });
        }
        PluginCommand tokensCommand = getCommand("tokens");
        if (tokensCommand != null) {
            tokensCommand.setExecutor((sender, cmd, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(prefix() + Text.color("&cOnly players can open the Token Mailbox."));
                    return true;
                }
                if (player.hasPermission("vipbridge.tokens.use")) tokenMailboxService.open(player);
                return true;
            });
        }

        PluginCommand headRedeemCommand = getCommand("headredeem");
        if (headRedeemCommand != null) headRedeemCommand.setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("Only players can redeem heads.");
                return true;
            }
            if (!player.hasPermission("vipbridge.heads.redeem")) { player.sendMessage(prefix() + Text.color("&cYou cannot redeem heads.")); return true; }
            if (args.length != 1) {
                player.sendMessage(prefix() + Text.color("&eUsage: /headredeem <HeadDB ID>"));
                headDBService.sendWebsite(player);
                return true;
            }
            try { headDBService.redeemById(player, Integer.parseInt(args[0])); }
            catch (NumberFormatException e) { player.sendMessage(prefix() + Text.color("&cInvalid head ID. Use the number from headdb.net.")); }
            return true;
        });
        PluginCommand headSearchCommand = getCommand("headsearch");
        if (headSearchCommand != null) headSearchCommand.setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player player) { if (player.hasPermission("vipbridge.heads.search")) headDBService.sendWebsite(player); }
            else sender.sendMessage("https://headdb.net/heads");
            return true;
        });

        getServer().getPluginManager().registerEvents(new AnnouncementListener(announcementService), this);
        getServer().getPluginManager().registerEvents(new TokenListener(tokenService), this);
        getServer().getPluginManager().registerEvents(new MailboxListener(mailboxService), this);
        getServer().getPluginManager().registerEvents(new TokenMailboxListener(this, tokenService, tokenMailboxService, shopService, headDBService, mailboxService, vipOnboardingService), this);
        getServer().getPluginManager().registerEvents(shopService, this);
        if (headDBHooked) {
            getServer().getPluginManager().registerEvents(headDBService, this);
        }

        // Rebuild assigned statues after startup so older/broken FancyNPC entries are repaired.
        getServer().getScheduler().runTaskLater(this, statueService::restoreAssignedStatues, 40L);

        getLogger().info("VIPBridge enabled. Virtual Token Mailbox/API ready.");
    }

    @Override
    public void onDisable() {
        if (tokensFile != null) {
            tokensFile.save();
        }

        if (statuesFile != null) {
            statuesFile.save();
        }

        if (mailboxesFile != null) {
            mailboxesFile.save();
        }

        if (vipDataFile != null) {
            vipDataFile.save();
        }

        if (announcementsFile != null) {
            announcementsFile.save();
        }
    }

    public void reloadAll() {
        reloadConfig();
        tokensFile.reload();
        messagesFile.reload();
        statuesFile.reload();
        mailboxesFile.reload();
        vipDataFile.reload();
        announcementsFile.reload();
    }

    private void saveResourceIfMissing(String name) {
        if (!getDataFolder().toPath().resolve(name).toFile().exists()) {
            saveResource(name, false);
        }
    }

    public String prefix() {
        return Text.color(messagesFile.getConfig().getString("prefix", "&6&lRewards &8» &f"));
    }

    private void migrateConfigDefaults() {
        boolean changed = false;
        if (!getConfig().isSet("vip.rewards.head-db-tokens")) { getConfig().set("vip.rewards.head-db-tokens", 1); changed = true; }
        if (!getConfig().isSet("onboarding.enabled")) { getConfig().set("onboarding.enabled", true); changed = true; }
        if (!getConfig().isSet("onboarding.title")) { getConfig().set("onboarding.title", "&6&lVIP QUICK GUIDE"); changed = true; }
        if (!getConfig().isSet("onboarding.lines")) {
            getConfig().set("onboarding.lines", java.util.List.of(
                    "&fYour VIP rewards are added to the same &eToken Mailbox &feveryone uses.",
                    "&e/tokens &7- View tokens, redeem rewards, and collect deliveries.",
                    "&e/tokenshop &7- Buy additional tokens with server money."
            ));
            changed = true;
        }
        if (!getConfig().isSet("onboarding.footer")) { getConfig().set("onboarding.footer", "&7Tip: run &e/vip &7any time to see this again."); changed = true; }
        // One-time command-name cleanup while preserving any custom guide wording.
        java.util.List<String> guideLines = getConfig().getStringList("onboarding.lines");
        java.util.List<String> cleanedGuideLines = guideLines.stream()
                .map(line -> line.replace("/tokenmailbox", "/tokens")
                        .replace("/tokenbox", "/tokens")
                        .replace("/rewards tokens", "/tokens")
                        .replace("/rewards shop", "/tokenshop")
                        .replace("/buytokens", "/tokenshop")
                        .replace("/rewards heads", "/tokens")
                        .replace("/rewards mailbox", "/tokens")
                        .replace("/vip heads", "/tokens")
                        .replace("/vip mailbox", "/tokens"))
                .toList();
        if (!cleanedGuideLines.equals(guideLines)) { getConfig().set("onboarding.lines", cleanedGuideLines); changed = true; }
        String guideFooter = getConfig().getString("onboarding.footer", "");
        String cleanedFooter = guideFooter.replace("/vip guide", "/vip").replace("/rewards help", "/vip");
        if (!cleanedFooter.equals(guideFooter)) { getConfig().set("onboarding.footer", cleanedFooter); changed = true; }
        if (!getConfig().isSet("forms.custom-map.instructions")) { getConfig().set("forms.custom-map.instructions", "Open the submission form and include your Token ID with your map request."); changed = true; }
        if (!getConfig().isSet("forms.custom-music.instructions")) { getConfig().set("forms.custom-music.instructions", "Open the submission form and include your Token ID with your music request."); changed = true; }
        if (!getConfig().isSet("forms.catchphrase.instructions")) { getConfig().set("forms.catchphrase.instructions", "Open the submission form and include your Token ID with your catchphrase request."); changed = true; }
        if (!getConfig().isSet("shop.prices.head-db")) { getConfig().set("shop.prices.head-db", 1000.0); changed = true; }
        if (!getConfig().isSet("shop.display.head-db")) { getConfig().set("shop.display.head-db", "&aHeadDB Token"); changed = true; }
        if (!getConfig().isSet("tokens.item.head-db-name")) { getConfig().set("tokens.item.head-db-name", "&aHeadDB Token"); changed = true; }
        if (!getConfig().isSet("client-support.java-only-token-guis")) { getConfig().set("client-support.java-only-token-guis", true); changed = true; }
        if (!getConfig().isSet("client-support.bedrock-message")) {
            getConfig().set("client-support.bedrock-message", "&eThe Token Mailbox, Token Shop, and custom reward redemption are currently supported on the &fJava Edition client &eonly. &7Your tokens remain safely saved and can be used whenever you join on Java."); changed = true;
        }
        if (!getConfig().isSet("onboarding.bedrock-lines")) {
            getConfig().set("onboarding.bedrock-lines", java.util.List.of(
                    "&eCustom reward tokens are currently redeemable on Java Edition only.",
                    "&7Your VIP tokens stay safely saved until you join using Java."
            )); changed = true;
        }
        if (!getConfig().isSet("token-mailbox.descriptions.custom-map")) { getConfig().set("token-mailbox.descriptions.custom-map", java.util.List.of("&7Turn your favorite image into a", "&7one-of-a-kind map for your builds.")); changed = true; }
        if (!getConfig().isSet("token-mailbox.descriptions.custom-music")) { getConfig().set("token-mailbox.descriptions.custom-music", java.util.List.of("&7Bring a song into the server as", "&7your own custom playable music disc.")); changed = true; }
        if (!getConfig().isSet("token-mailbox.descriptions.catchphrase")) { getConfig().set("token-mailbox.descriptions.catchphrase", java.util.List.of("&7Give your VIP statue some personality.", "&7Choose the line it says to visitors.")); changed = true; }
        if (!getConfig().isSet("token-mailbox.descriptions.head-db")) { getConfig().set("token-mailbox.descriptions.head-db", java.util.List.of("&7Pick a decorative head from HeadDB.", "&71 token unlocks 1 head of your choice.")); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.max-file-size-mb")) {
            getConfig().set("music.url-custom-discs.max-file-size-mb", getConfig().getLong("music.url-custom-discs.max-mp3-size-mb", 12)); changed = true;
        }
        if (!getConfig().isSet("music.url-custom-discs.connect-timeout-seconds")) { getConfig().set("music.url-custom-discs.connect-timeout-seconds", 15); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.read-timeout-seconds")) { getConfig().set("music.url-custom-discs.read-timeout-seconds", 60); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.download-timeout-seconds")) { getConfig().set("music.url-custom-discs.download-timeout-seconds", 180); changed = true; }
        if (getConfig().contains("music.url-custom-discs.require-staff-online-during-capture")) { getConfig().set("music.url-custom-discs.require-staff-online-during-capture", null); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.runner-wait-timeout-seconds")) { getConfig().set("music.url-custom-discs.runner-wait-timeout-seconds", 300); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.metadata-file")) { getConfig().set("music.url-custom-discs.metadata-file", "plugins/URLCustomDiscs/discs.json"); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.independent-capture")) { getConfig().set("music.url-custom-discs.independent-capture", true); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.reset-processing-on-startup")) { getConfig().set("music.url-custom-discs.reset-processing-on-startup", true); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.console-logging")) { getConfig().set("music.url-custom-discs.console-logging", true); changed = true; }
        if (!getConfig().isSet("music.url-custom-discs.progress-log-percent")) { getConfig().set("music.url-custom-discs.progress-log-percent", 25); changed = true; }
        if (getConfig().contains("music.url-custom-discs.max-mp3-size-mb")) { getConfig().set("music.url-custom-discs.max-mp3-size-mb", null); changed = true; }
        // Bedrock token GUIs are now intentionally blocked, so the old copy-link form settings are obsolete.
        if (getConfig().contains("bedrock-link-form")) { getConfig().set("bedrock-link-form", null); changed = true; }
        if (getConfig().contains("headdb.bedrock-form-browser")) { getConfig().set("headdb.bedrock-form-browser", null); changed = true; }
        if (!getConfig().isSet("sounds.token-purchase-success.enabled")) {
            getConfig().set("sounds.token-purchase-success.enabled", true);
            getConfig().set("sounds.token-purchase-success.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
            getConfig().set("sounds.token-purchase-success.volume", 1.0);
            getConfig().set("sounds.token-purchase-success.pitch", 1.2);
            changed = true;
        }
        if (!getConfig().isSet("sounds.token-purchase-failure.enabled")) {
            getConfig().set("sounds.token-purchase-failure.enabled", true);
            getConfig().set("sounds.token-purchase-failure.sound", "ENTITY_ENDERMAN_TELEPORT");
            getConfig().set("sounds.token-purchase-failure.volume", 0.7);
            getConfig().set("sounds.token-purchase-failure.pitch", 0.8);
            changed = true;
        }
        // Repair paths from the older config where these sections were accidentally nested under shoutouts.
        if (!getConfig().isSet("mailbox.size")) {
            getConfig().set("mailbox.size", getConfig().getInt("shoutouts.mailbox.size", 27)); changed = true;
        }
        if (!getConfig().isSet("shoutouts.sound.enabled")) {
            getConfig().set("shoutouts.sound.enabled", true);
            getConfig().set("shoutouts.sound.sound", getConfig().getString("sounds.vip-grant.sound", "UI_TOAST_CHALLENGE_COMPLETE"));
            getConfig().set("shoutouts.sound.volume", getConfig().getDouble("sounds.vip-grant.volume", 1.0));
            getConfig().set("shoutouts.sound.pitch", getConfig().getDouble("sounds.vip-grant.pitch", 1.0));
            changed = true;
        }
        if (getConfig().contains("onboarding.sound")) { getConfig().set("onboarding.sound", null); changed = true; }
        if (getConfig().contains("sounds.vip-grant")) { getConfig().set("sounds.vip-grant", null); changed = true; }
        if (!getConfig().isSet("vip.reset-commands.enabled")) {
            getConfig().set("vip.reset-commands.enabled", getConfig().getBoolean("shoutouts.reset-commands.enabled", true));
            java.util.List<String> commands = getConfig().getStringList("shoutouts.reset-commands.commands");
            if (commands.isEmpty()) commands = java.util.List.of("lp user {player} demote main");
            getConfig().set("vip.reset-commands.commands", commands); changed = true;
        }
        if (changed) saveConfig();
    }

    private void normalizeGenericMessages() {
        // Generic token/shop/mailbox messages must work for non-VIP players too.
        messagesFile.getConfig().set("prefix", "&6&lRewards &8» &f");
        messagesFile.getConfig().set("unknown-command", "&cUnknown VIP command. Use &e/vip&c.");
        messagesFile.getConfig().set("token-redeem-header", "&6Request token redeemed. Complete the form below.");
        messagesFile.getConfig().set("token-list-empty", "&7No tokens found.");
        messagesFile.getConfig().set("shop-header", "&6Token Shop");
        messagesFile.getConfig().set("shop-footer", "&7Use &e/tokenshop &7to buy another token.");
        messagesFile.getConfig().set("shop-invalid-token", "&cUnknown token type. Use: &emap&c, &emusic&c, or &ehead&c.");
        // Migrate the stock music wording too. These are operational status messages,
        // so they should always reflect the current job state instead of leaving a stale
        // PROCESSING message/reason from an earlier attempt.
        String oldDownloadFailure = "&cMP3 download failed: &e{reason}";
        if (!messagesFile.getConfig().isSet("music-download-failed") || oldDownloadFailure.equals(messagesFile.getConfig().getString("music-download-failed"))) {
            messagesFile.getConfig().set("music-download-failed", "&cApproval failed: &e{reason} &7Token returned to &aUNUSED&7.");
        }
        String oldProcessing = "&aMP3 downloaded. URLCustomDiscs is generating &e{disc_name}&a...";
        if (!messagesFile.getConfig().isSet("music-processing") || oldProcessing.equals(messagesFile.getConfig().getString("music-processing"))) {
            messagesFile.getConfig().set("music-processing", "&aMP3 downloaded. URLCustomDiscs is generating &e{disc_name}&a... &7Staff may disconnect; VIPBridge will keep watching the job.");
        }
        if (!messagesFile.getConfig().isSet("music-another-job-active")) messagesFile.getConfig().set("music-another-job-active", "&eAnother custom music disc is already being generated. Try this approval again when it finishes.");
        if (!messagesFile.getConfig().isSet("music-waiting-for-runner")) messagesFile.getConfig().set("music-waiting-for-runner", "&eMP3 downloaded. Waiting for an eligible staff player to be online so URLCustomDiscs can start; the token remains PROCESSING.");
        if (!messagesFile.getConfig().isSet("music-runner-timeout")) messagesFile.getConfig().set("music-runner-timeout", "&cNo eligible staff player became available to start URLCustomDiscs. Token returned to &aUNUSED&c.");
        messagesFile.save();
    }


    public VIPBridgeAPI api() { return api; }

}