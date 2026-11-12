package com.errsa.vipbridge.tokens;

import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.config.YamlFile;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.util.Text;
import com.errsa.vipbridge.util.ClickableMessages;
import com.errsa.vipbridge.util.BedrockLinkForm;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TokenService {

    private final VIPBridgePlugin plugin;
    private final YamlFile tokensFile;
    private final YamlFile messagesFile;
    private final MailboxService mailboxService;
    private final NamespacedKey tokenKey;
    private final SecureRandom random = new SecureRandom();

    public TokenService(VIPBridgePlugin plugin, YamlFile tokensFile, YamlFile messagesFile, MailboxService mailboxService) {
        this.plugin = plugin;
        this.tokensFile = tokensFile;
        this.messagesFile = messagesFile;
        this.mailboxService = mailboxService;
        this.tokenKey = new NamespacedKey(plugin, "token_id");
    }

    /**
     * Creates virtual tokens. Tokens are stored in tokens.yml and never enter a
     * player inventory, so PerWorldInventory/world changes cannot lose them.
     */
    public List<String> createTokens(OfflinePlayer owner, TokenType type, int amount) {
        return createTokens(owner, type, amount, true);
    }

    /**
     * Creates virtual tokens with optional player-facing notification. Shop purchases
     * use notify=false so the dedicated bank transaction receipt is the only message/sound.
     */
    public List<String> createTokens(OfflinePlayer owner, TokenType type, int amount, boolean notify) {
        List<String> ids = new ArrayList<>();
        if (owner == null || type == null || amount < 1) return ids;

        String ownerName = owner.getName() == null ? owner.getUniqueId().toString() : owner.getName();
        String base = "players." + owner.getUniqueId();
        tokensFile.getConfig().set(base + ".name", ownerName);

        for (int i = 0; i < amount; i++) {
            String tokenId = generateTokenId(type);
            String path = base + ".tokens." + tokenId;
            tokensFile.getConfig().set(path + ".type", type.name());
            tokensFile.getConfig().set(path + ".status", TokenStatus.UNUSED.name());
            tokensFile.getConfig().set(path + ".form", type.formKey());
            tokensFile.getConfig().set(path + ".url", "");
            tokensFile.getConfig().set(path + ".approved-by", "");
            tokensFile.getConfig().set(path + ".created-at", Instant.now().toString());
            tokensFile.getConfig().set(path + ".fulfilled", false);
            ids.add(tokenId);
        }

        tokensFile.save();

        Player online = owner.getPlayer();
        if (notify && online != null && online.isOnline()) {
            ClickableMessages.sendTokenAdded(online, type.displayName(), amount, countAvailableTokens(owner.getUniqueId()));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        }
        return ids;
    }

    private boolean giveItemToInventory(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());

        if (!leftover.isEmpty()) {
            return false;
        }

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        return true;
    }

    public ItemStack buildTokenItem(String tokenId, TokenType type) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("tokens.item.material", "PAPER"));

        if (material == null) {
            material = Material.PAPER;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        String namePath = "tokens.item." + type.nameConfigKey();
        String displayName = plugin.getConfig().getString(namePath, "&eReward Token");

        meta.setDisplayName(Text.color(displayName));

        List<String> loreTemplate = plugin.getConfig().getStringList("tokens.item.lore");
        List<String> lore = new ArrayList<>();

        for (String line : loreTemplate) {
            lore.add(Text.color(line
                    .replace("{token_id}", tokenId)
                    .replace("{type}", type.name())));
        }

        meta.setLore(lore);
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, tokenId);

        item.setItemMeta(meta);
        return item;
    }

    public Optional<String> getTokenId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }

        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return Optional.empty();
        }

        String id = meta.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);

        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(id);
    }

    /** Redeems a virtual request token. HeadDB tokens are consumed when a head is selected. */
    public void redeem(Player player, String tokenId) {
        TokenLookup lookup = findToken(tokenId);
        if (lookup == null) { send(player, "token-not-found"); return; }
        if (!lookup.ownerUuid().equals(player.getUniqueId().toString())) { send(player, "token-not-yours"); return; }

        String status = tokensFile.getConfig().getString(lookup.path() + ".status", "UNUSED");
        if (!TokenStatus.UNUSED.name().equalsIgnoreCase(status)) {
            player.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString("token-already-used", "&cThat token is already {status}.")
                    .replace("{status}", status)));
            return;
        }

        TokenType type;
        try { type = TokenType.valueOf(tokensFile.getConfig().getString(lookup.path() + ".type", "CUSTOM_MAP")); }
        catch (IllegalArgumentException ex) { player.sendMessage(plugin.prefix() + Text.color("&cThis token has an invalid type. Please contact staff.")); return; }

        if (type == TokenType.HEADDB) {
            // Choosing the HeadDB token itself only opens the browser. The token is
            // consumed by HeadDBService after a specific head is selected.
            Bukkit.dispatchCommand(player, "vip heads");
            return;
        }

        String url = plugin.getConfig().getString("forms." + type.formKey() + ".url", "");
        boolean enabled = plugin.getConfig().getBoolean("forms." + type.formKey() + ".enabled", true);
        if (!enabled || url.isBlank()) {
            player.sendMessage(plugin.prefix() + Text.color("&cThis request form is not currently available. Please contact staff."));
            return;
        }

        // Clicking the virtual token spends it immediately, as requested. Staff
        // processing accepts REDEEMED tokens so the existing approval workflow continues.
        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.REDEEMED.name());
        tokensFile.getConfig().set(lookup.path() + ".redeemed-at", Instant.now().toString());
        tokensFile.save();

        player.closeInventory();
        player.sendMessage("");
        player.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "token-redeem-header", "&6Request token redeemed. Complete the form below.")));

        String instructions = plugin.getConfig().getString(
                "forms." + type.formKey() + ".instructions",
                "Use the website below to submit your request. Keep your Token ID for the form."
        );
        String formTitle = Text.stripColor(type.displayName() + " Request");
        if (BedrockLinkForm.isBedrock(player, plugin)) {
            boolean opened = BedrockLinkForm.show(plugin, player, formTitle, instructions, url, tokenId);
            if (opened) {
                player.sendMessage(Text.color("&bBedrock &8» &fA copy-friendly form was opened. Tap the website field, select all, and copy it."));
            } else {
                player.sendMessage(Text.color("&eBedrock &8» &fThe copy form could not open, so use the plain website below."));
            }
            // Keep a plain-text fallback visible in chat for consoles/API incompatibilities.
            if (plugin.getConfig().getBoolean("bedrock-link-form.send-chat-fallback", true) || !opened) {
                player.sendMessage(Text.color("&7Website: &e" + url));
                player.sendMessage(Text.color("&7Token ID: &e" + tokenId));
            }
        } else {
            if (instructions != null && !instructions.isBlank()) player.sendMessage(Text.color("&7" + instructions));
            ClickableMessages.sendExternalFormLink(player, url, tokenId);
        }
        player.sendMessage(Text.color("&7This token is now marked as redeemed."));
        player.sendMessage("");
    }

    public boolean approve(CommandSender sender, String tokenId, String value) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return false;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.APPROVED.name());
        tokensFile.getConfig().set(lookup.path() + ".url", value == null ? "" : value);
        tokensFile.getConfig().set(lookup.path() + ".approved-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".approved-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", true);
        tokensFile.getConfig().set(lookup.path() + ".fulfilled-at", Instant.now().toString());

        consumePhysicalToken(UUID.fromString(lookup.ownerUuid()), tokenId);

        tokensFile.save();

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "token-approved",
                "&aApproved token &e{token_id}&a."
        ).replace("{token_id}", tokenId)));

        return true;
    }

    public boolean deny(CommandSender sender, String tokenId, String reason) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return false;
        }

        String typeName = tokensFile.getConfig().getString(lookup.path() + ".type", "CUSTOM_MAP");
        TokenType replacementType;

        try {
            replacementType = TokenType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(plugin.prefix() + Text.color("&cCould not replace token because the saved token type is invalid: &e" + typeName));
            return false;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.DENIED.name());
        tokensFile.getConfig().set(lookup.path() + ".denied-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".deny-reason", reason == null ? "" : reason);
        tokensFile.getConfig().set(lookup.path() + ".denied-at", Instant.now().toString());


        tokensFile.save();

        consumePhysicalToken(UUID.fromString(lookup.ownerUuid()), tokenId);


        OfflinePlayer owner = Bukkit.getOfflinePlayer(UUID.fromString(lookup.ownerUuid()));
        List<String> newTokens = createTokens(owner, replacementType, 1);
        String newTokenId = newTokens.isEmpty() ? "NONE" : newTokens.get(0);

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "token-denied-replaced",
                "&cDenied token &e{old_token}&c. Replacement token created: &a{new_token}&c."
        ).replace("{old_token}", tokenId).replace("{new_token}", newTokenId)));

        return true;
    }

    public void listTokens(CommandSender sender, OfflinePlayer target) {
        String base = "players." + target.getUniqueId() + ".tokens";
        ConfigurationSection section = tokensFile.getConfig().getConfigurationSection(base);

        if (section == null || section.getKeys(false).isEmpty()) {
            send(sender, "token-list-empty");
            return;
        }

        sender.sendMessage(Text.color("&6Tokens for &e" + target.getName() + "&6:"));

        for (String tokenId : section.getKeys(false)) {
            String path = base + "." + tokenId;
            String type = tokensFile.getConfig().getString(path + ".type", "UNKNOWN");
            String status = tokensFile.getConfig().getString(path + ".status", "UNKNOWN");

            sender.sendMessage(Text.color("&8- &e" + tokenId + " &7| &f" + type + " &7| &a" + status));
        }
    }

    public boolean markMapProcessing(
            CommandSender sender,
            String tokenId,
            String mapName,
            String sourceUrl
    ) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return false;
        }

        String typeName = tokensFile.getConfig().getString(lookup.path() + ".type", "");

        if (!TokenType.CUSTOM_MAP.name().equalsIgnoreCase(typeName)) {
            send(sender, "map-invalid-token");
            return false;
        }

        if (!isProcessable(tokenId)) {
            String status = getTokenStatus(tokenId);

            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "token-already-used",
                    "&cThat token is already marked as &e{status}&c."
            ).replace("{status}", status)));

            return false;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.PROCESSING.name());
        tokensFile.getConfig().set(lookup.path() + ".map-name", mapName);
        tokensFile.getConfig().set(lookup.path() + ".source-url", sourceUrl);
        tokensFile.getConfig().set(lookup.path() + ".processing-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".processing-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", false);
        tokensFile.save();

        return true;
    }

    public void fulfillMapToken(
            CommandSender sender,
            String tokenId,
            String mapName,
            int imageIndex
    ) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            return;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.APPROVED.name());
        tokensFile.getConfig().set(lookup.path() + ".map-name", mapName);
        tokensFile.getConfig().set(lookup.path() + ".imageframe-image-index", imageIndex);
        tokensFile.getConfig().set(lookup.path() + ".approved-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".approved-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", true);
        tokensFile.getConfig().set(lookup.path() + ".fulfilled-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".mailbox-delivered", true);

        consumePhysicalToken(UUID.fromString(lookup.ownerUuid()), tokenId);

        tokensFile.save();
    }

    public void resetMapTokenToUnused(String tokenId, String reason) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            return;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.UNUSED.name());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", false);
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-reason", reason);
        tokensFile.getConfig().set(lookup.path() + ".last-error", reason);
        tokensFile.getConfig().set(lookup.path() + ".processing-by", null);
        tokensFile.getConfig().set(lookup.path() + ".processing-at", null);
        tokensFile.save();
    }

    public TokenLookup findToken(String tokenId) {
        ConfigurationSection players = tokensFile.getConfig().getConfigurationSection("players");

        if (players == null) {
            return null;
        }

        for (String uuid : players.getKeys(false)) {
            String path = "players." + uuid + ".tokens." + tokenId;

            if (tokensFile.getConfig().contains(path)) {
                return new TokenLookup(uuid, path);
            }
        }

        return null;
    }

    private String generateTokenId(TokenType type) {
        String tokenId;

        do {
            tokenId = type.prefix() + "-" + randomText(8);
        } while (findToken(tokenId) != null);

        return tokenId;
    }

    private String randomText(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }

        return sb.toString();
    }

    private void send(CommandSender sender, String messagePath) {
        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(messagePath, messagePath)));
    }

    public record TokenLookup(String ownerUuid, String path) {
    }

    public UUID approveCatchphraseToken(CommandSender sender, String tokenId, String catchphrase) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return null;
        }

        String typeName = tokensFile.getConfig().getString(lookup.path() + ".type", "");

        if (!TokenType.CATCHPHRASE.name().equalsIgnoreCase(typeName)) {
            send(sender, "catchphrase-token-wrong-type");
            return null;
        }

        String status = tokensFile.getConfig().getString(lookup.path() + ".status", "UNUSED");

        if (!(TokenStatus.UNUSED.name().equalsIgnoreCase(status) || TokenStatus.REDEEMED.name().equalsIgnoreCase(status))) {
            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "token-already-used",
                    "&cThat token is already marked as &e{status}&c."
            ).replace("{status}", status)));
            return null;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.APPROVED.name());
        tokensFile.getConfig().set(lookup.path() + ".catchphrase", catchphrase);
        tokensFile.getConfig().set(lookup.path() + ".approved-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".approved-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", true);
        tokensFile.getConfig().set(lookup.path() + ".fulfilled-at", Instant.now().toString());

        consumePhysicalToken(UUID.fromString(lookup.ownerUuid()), tokenId);

        tokensFile.save();

        return UUID.fromString(lookup.ownerUuid());
    }

    public void consumePhysicalToken(UUID ownerUuid, String tokenId) {
        Player online = Bukkit.getPlayer(ownerUuid);

        if (online != null && online.isOnline()) {
            ItemStack[] contents = online.getInventory().getContents();

            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];

                if (item == null) {
                    continue;
                }

                Optional<String> savedTokenId = getTokenId(item);

                if (savedTokenId.isEmpty() || !savedTokenId.get().equalsIgnoreCase(tokenId)) {
                    continue;
                }

                if (item.getAmount() <= 1) {
                    online.getInventory().setItem(slot, null);
                } else {
                    item.setAmount(item.getAmount() - 1);
                    online.getInventory().setItem(slot, item);
                }
            }
        }

        mailboxService.removeTokenItems(ownerUuid, tokenKey, tokenId);
    }

    public boolean replaceToken(CommandSender sender, String tokenId) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return false;
        }

        String typeName = tokensFile.getConfig().getString(lookup.path() + ".type", "CUSTOM_MAP");
        TokenType replacementType;

        try {
            replacementType = TokenType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            send(sender, "token-replace-failed");
            return false;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.DENIED.name());
        tokensFile.getConfig().set(lookup.path() + ".replaced-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".replaced-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".deny-reason", "Replaced by staff");
        tokensFile.save();

        consumePhysicalToken(UUID.fromString(lookup.ownerUuid()), tokenId);

        OfflinePlayer owner = Bukkit.getOfflinePlayer(UUID.fromString(lookup.ownerUuid()));
        List<String> newTokens = createTokens(owner, replacementType, 1);

        if (newTokens.isEmpty()) {
            send(sender, "token-replace-failed");
            return false;
        }

        sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                "token-replaced",
                "&aReplaced token &e{old_token}&a with &e{new_token}&a."
        ).replace("{old_token}", tokenId).replace("{new_token}", newTokens.get(0))));

        return true;
    }

    public void clearPlayerTokens(UUID uuid) {
        tokensFile.getConfig().set("players." + uuid, null);
        tokensFile.save();
    }

    public int countTokens(UUID uuid) {
        ConfigurationSection section = tokensFile.getConfig().getConfigurationSection("players." + uuid + ".tokens");

        if (section == null) {
            return 0;
        }

        return section.getKeys(false).size();
    }

    public TokenType getTokenType(String tokenId) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            return null;
        }

        String typeName = tokensFile.getConfig().getString(lookup.path() + ".type", "");

        try {
            return TokenType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String getTokenStatus(String tokenId) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            return "";
        }

        return tokensFile.getConfig().getString(lookup.path() + ".status", "");
    }

    public boolean isUnused(String tokenId) {
        return TokenStatus.UNUSED.name().equalsIgnoreCase(getTokenStatus(tokenId));
    }

    /** A submitted form token can be UNUSED (legacy/manual) or REDEEMED (GUI workflow). */
    public boolean isProcessable(String tokenId) {
        String status = getTokenStatus(tokenId);
        return TokenStatus.UNUSED.name().equalsIgnoreCase(status)
                || TokenStatus.REDEEMED.name().equalsIgnoreCase(status);
    }

    public boolean markMusicProcessing(
            CommandSender sender,
            String tokenId,
            String discName,
            String sourceUrl
    ) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            send(sender, "token-not-found");
            return false;
        }

        String typeName = tokensFile.getConfig().getString(lookup.path() + ".type", "");

        if (!TokenType.CUSTOM_MUSIC.name().equalsIgnoreCase(typeName)) {
            send(sender, "music-invalid-token");
            return false;
        }

        if (!isProcessable(tokenId)) {
            String status = getTokenStatus(tokenId);

            sender.sendMessage(plugin.prefix() + Text.color(messagesFile.getConfig().getString(
                    "token-already-used",
                    "&cThat token is already marked as &e{status}&c."
            ).replace("{status}", status)));

            return false;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.PROCESSING.name());
        tokensFile.getConfig().set(lookup.path() + ".disc-name", discName);
        tokensFile.getConfig().set(lookup.path() + ".source-url", sourceUrl);
        tokensFile.getConfig().set(lookup.path() + ".processing-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".processing-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", false);
        // Clear diagnostics from an earlier failed attempt so staff never see a stale
        // failure reason underneath a new PROCESSING job.
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-at", null);
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-reason", null);
        tokensFile.getConfig().set(lookup.path() + ".last-error", null);
        tokensFile.save();

        return true;
    }

    public void fulfillMusicToken(
            CommandSender sender,
            String tokenId,
            String fileName
    ) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            return;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.APPROVED.name());
        tokensFile.getConfig().set(lookup.path() + ".file-name", fileName);
        tokensFile.getConfig().set(lookup.path() + ".approved-by", sender.getName());
        tokensFile.getConfig().set(lookup.path() + ".approved-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", true);
        tokensFile.getConfig().set(lookup.path() + ".fulfilled-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".mailbox-delivered", true);
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-at", null);
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-reason", null);
        tokensFile.getConfig().set(lookup.path() + ".last-error", null);
        consumePhysicalToken(UUID.fromString(lookup.ownerUuid()), tokenId);
        tokensFile.save();
    }

    public void resetMusicTokenToUnused(String tokenId, String reason) {
        TokenLookup lookup = findToken(tokenId);

        if (lookup == null) {
            return;
        }

        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.UNUSED.name());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", false);
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".processing-reset-reason", reason);
        tokensFile.getConfig().set(lookup.path() + ".last-error", reason);
        tokensFile.getConfig().set(lookup.path() + ".processing-by", null);
        tokensFile.getConfig().set(lookup.path() + ".processing-at", null);
        tokensFile.save();
    }



    /**
     * Processing jobs are in-memory asynchronous work and cannot survive a server restart.
     * Any CUSTOM_MUSIC token still marked PROCESSING at startup is therefore orphaned
     * and safe to return to UNUSED for a clean retry.
     */
    public int resetOrphanedMusicProcessingTokens() {
        ConfigurationSection players = tokensFile.getConfig().getConfigurationSection("players");
        if (players == null) return 0;
        int recovered = 0;
        for (String uuid : players.getKeys(false)) {
            ConfigurationSection section = tokensFile.getConfig().getConfigurationSection("players." + uuid + ".tokens");
            if (section == null) continue;
            for (String id : section.getKeys(false)) {
                String path = "players." + uuid + ".tokens." + id;
                if (!TokenType.CUSTOM_MUSIC.name().equalsIgnoreCase(tokensFile.getConfig().getString(path + ".type", ""))) continue;
                if (!TokenStatus.PROCESSING.name().equalsIgnoreCase(tokensFile.getConfig().getString(path + ".status", ""))) continue;
                tokensFile.getConfig().set(path + ".status", TokenStatus.UNUSED.name());
                tokensFile.getConfig().set(path + ".processing-recovery", "Reset on startup after interrupted processing job");
                tokensFile.getConfig().set(path + ".processing-recovered-at", Instant.now().toString());
                recovered++;
            }
        }
        if (recovered > 0) tokensFile.save();
        return recovered;
    }

    public int countAvailableTokens(UUID uuid) {
        int total = 0;
        for (TokenType type : TokenType.values()) total += countAvailableTokens(uuid, type);
        return total;
    }

    public int countAvailableTokens(UUID uuid, TokenType type) {
        ConfigurationSection section = tokensFile.getConfig().getConfigurationSection("players." + uuid + ".tokens");
        if (section == null) return 0;
        int count = 0;
        for (String id : section.getKeys(false)) {
            String path = "players." + uuid + ".tokens." + id;
            if (type.name().equalsIgnoreCase(tokensFile.getConfig().getString(path + ".type", ""))
                    && TokenStatus.UNUSED.name().equalsIgnoreCase(tokensFile.getConfig().getString(path + ".status", ""))) count++;
        }
        return count;
    }

    public List<TokenRecord> getAvailableTokens(UUID uuid) {
        List<TokenRecord> result = new ArrayList<>();
        ConfigurationSection section = tokensFile.getConfig().getConfigurationSection("players." + uuid + ".tokens");
        if (section == null) return result;
        for (String id : section.getKeys(false)) {
            String path = "players." + uuid + ".tokens." + id;
            if (!TokenStatus.UNUSED.name().equalsIgnoreCase(tokensFile.getConfig().getString(path + ".status", ""))) continue;
            try {
                TokenType type = TokenType.valueOf(tokensFile.getConfig().getString(path + ".type", ""));
                result.add(new TokenRecord(id, type, tokensFile.getConfig().getString(path + ".created-at", "")));
            } catch (IllegalArgumentException ignored) { }
        }
        result.sort(java.util.Comparator.comparing(TokenRecord::createdAt));
        return result;
    }

    public String findFirstAvailableToken(UUID uuid, TokenType type) {
        for (TokenRecord token : getAvailableTokens(uuid)) if (token.type() == type) return token.id();
        return null;
    }

    /** Consumes one virtual token for an immediate reward such as a HeadDB head. */
    public String consumeOne(UUID uuid, TokenType type, String source) {
        String id = findFirstAvailableToken(uuid, type);
        if (id == null) return null;
        TokenLookup lookup = findToken(id);
        if (lookup == null) return null;
        tokensFile.getConfig().set(lookup.path() + ".status", TokenStatus.APPROVED.name());
        tokensFile.getConfig().set(lookup.path() + ".fulfilled", true);
        tokensFile.getConfig().set(lookup.path() + ".fulfilled-at", Instant.now().toString());
        tokensFile.getConfig().set(lookup.path() + ".consumed-source", source == null ? "api" : source);
        tokensFile.save();
        consumePhysicalToken(uuid, id); // migration cleanup for old physical tokens only
        return id;
    }

    public boolean shouldSendDailyReminder(UUID uuid) {
        String base = "players." + uuid;
        String today = java.time.LocalDate.now().toString();
        String last = tokensFile.getConfig().getString(base + ".last-token-reminder", "");
        if (today.equals(last)) return false;
        tokensFile.getConfig().set(base + ".last-token-reminder", today);
        tokensFile.save();
        return true;
    }

    /** Removes legacy physical token items after upgrading to the virtual mailbox. */
    public void migrateLegacyPhysicalTokens(Player player) {
        if (player == null) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            Optional<String> id = getTokenId(item);
            if (id.isPresent() && findToken(id.get()) != null) player.getInventory().setItem(slot, null);
        }
        // Also purge every legacy physical token item that was previously stored in the
        // item mailbox, including tokens that were already redeemed/approved before the upgrade.
        ConfigurationSection tokenSection = tokensFile.getConfig().getConfigurationSection(
                "players." + player.getUniqueId() + ".tokens"
        );
        if (tokenSection != null) {
            for (String tokenId : tokenSection.getKeys(false)) {
                mailboxService.removeTokenItems(player.getUniqueId(), tokenKey, tokenId);
            }
        }
    }


    public record TokenRecord(String id, TokenType type, String createdAt) {}

}