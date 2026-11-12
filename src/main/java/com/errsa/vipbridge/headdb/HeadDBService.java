package com.errsa.vipbridge.headdb;

import com.bitworksmc.headdb.api.model.Head;
import com.bitworksmc.headdb.core.HeadDB;
import com.errsa.vipbridge.VIPBridgePlugin;
import com.errsa.vipbridge.mailbox.MailboxService;
import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenType;
import com.errsa.vipbridge.util.Text;
import com.errsa.vipbridge.util.BedrockLinkForm;
import com.errsa.vipbridge.util.BedrockForms;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.List;
import java.util.Comparator;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HeadDBService implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int BEDROCK_PAGE_SIZE = 20;
    private final VIPBridgePlugin plugin;
    private final TokenService tokens;
    private final MailboxService itemMailbox;
    private HeadDB headDB;
    private final Set<UUID> pendingRedemptions = new HashSet<>();

    public HeadDBService(VIPBridgePlugin plugin, TokenService tokens, MailboxService itemMailbox) {
        this.plugin=plugin; this.tokens=tokens; this.itemMailbox=itemMailbox;
    }

    public boolean hook() {
        Plugin found=Bukkit.getPluginManager().getPlugin("HeadDB");
        if(found instanceof HeadDB h){headDB=h; return true;}
        return false;
    }
    public boolean available(){return headDB!=null && headDB.isEnabled();}

    public void open(Player player,int page) {
        if(!available()){player.sendMessage(plugin.prefix()+Text.color("&cHeadDB is not available right now."));return;}
        if(tokens.countAvailableTokens(player.getUniqueId(),TokenType.HEADDB)<1){player.sendMessage(plugin.prefix()+Text.color("&cYou need a HeadDB Token. Open &e/tokenshop &cto buy one."));return;}
        if (plugin.getConfig().getBoolean("headdb.bedrock-form-browser.enabled", true) && BedrockLinkForm.isBedrock(player, plugin)) {
            openBedrockHome(player);
            return;
        }
        player.sendMessage(plugin.prefix()+Text.color("&7Loading HeadDB..."));
        headDB.getHeadApi().getHeads().thenAccept(heads -> Bukkit.getScheduler().runTask(plugin,()->openLoaded(player,heads,page)));
    }

    /** Redemption by website catalog ID: HeadDB lookup succeeds before a token is charged. */
    public void redeemById(Player p, int id) {
        if (!p.hasPermission("vipbridge.heads.redeem")) { p.sendMessage(plugin.prefix()+Text.color("&cYou cannot redeem heads.")); return; }
        if (!pendingRedemptions.add(p.getUniqueId())) { p.sendMessage(plugin.prefix()+Text.color("&eA head redemption is already processing.")); return; }
        redeemByIdLocked(p,id);
    }

    private void redeemByIdLocked(Player p, int id) {
        if (!available()) {
            p.sendMessage(plugin.prefix()+Text.color("&cHeadDB is unavailable right now. No tokens used."));
            pendingRedemptions.remove(p.getUniqueId());
            return;
        }
        if (id <= 0) {
            p.sendMessage(plugin.prefix()+Text.color("&cInvalid HeadDB ID. No tokens used."));
            pendingRedemptions.remove(p.getUniqueId());
            return;
        }
        if (tokens.countAvailableTokens(p.getUniqueId(), TokenType.HEADDB) < 1) {
            decline(p);
            pendingRedemptions.remove(p.getUniqueId());
            return;
        }
        try {
        headDB.getHeadApi().findById(id).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) { pendingRedemptions.remove(p.getUniqueId()); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
            try {
            if (!p.isOnline()) return;
            if (error != null || result == null || result.isEmpty()) {
                p.sendMessage(plugin.prefix()+Text.color("&cHead #"+id+" is not available in the server's current HeadDB catalog. No token used."));
                pendingRedemptions.remove(p.getUniqueId());
            return;
            }
            purchase(p, result.get());
            } finally { pendingRedemptions.remove(p.getUniqueId()); }
        });
        });
        } catch (Exception ex) {
            pendingRedemptions.remove(p.getUniqueId());
            p.sendMessage(plugin.prefix()+Text.color("&cCould not look up that head. No token used."));
        }
    }

    private void decline(Player p) {
        p.sendMessage(plugin.prefix()+Text.color("&cTransaction declined: You have no HeadDB tokens."));
        p.sendMessage(Component.text("  No token was used. ", NamedTextColor.GRAY)
                .append(Component.text("[Visit /tokenshop]", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/tokenshop"))
                        .hoverEvent(HoverEvent.showText(Component.text("Buy a HeadDB token")))));
    }

    public void sendWebsite(Player p) {
        if (!p.hasPermission("vipbridge.heads.search")) return;
        p.sendMessage(Component.text("HeadDB » ", NamedTextColor.GOLD)
                .append(Component.text("[Browse and search heads online]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl("https://headdb.net/heads"))
                        .hoverEvent(HoverEvent.showText(Component.text("Open HeadDB's searchable website")))));
        p.sendMessage(Text.color("&7Found a head? Redeem it using &e/headredeem <ID>&7. Each head costs 1 HeadDB token."));
    }

    private void openBedrockHome(Player p) {
        List<BedrockForms.FormButton> buttons = new ArrayList<>();
        buttons.add(new BedrockForms.FormButton("Search Heads", () -> openBedrockSearch(p)));
        List<String> categories = new ArrayList<>(headDB.getHeadApi().findKnownCategories());
        categories.sort(String.CASE_INSENSITIVE_ORDER);
        for (String category : categories) {
            buttons.add(new BedrockForms.FormButton(pretty(category), () -> loadBedrockCategory(p, category)));
        }
        buttons.add(new BedrockForms.FormButton("Back to Tokens", () -> Bukkit.dispatchCommand(p, "tokens")));
        String content = "Head Tokens: " + tokens.countAvailableTokens(p.getUniqueId(), TokenType.HEADDB)
                + "\nChoose a category or search by name.\nBedrock uses this list because Java custom skull previews render as Steve heads.";
        if (!BedrockForms.sendSimple(plugin, p, "HeadDB", content, buttons)) {
            p.sendMessage(plugin.prefix()+Text.color("&cCould not open the Bedrock HeadDB browser."));
        }
    }

    private void openBedrockSearch(Player p) {
        BedrockForms.sendInput(plugin, p, "Search HeadDB", "Enter part of a head name.", "Search", "Example: fox", query -> {
            String q = query == null ? "" : query.trim();
            if (q.isEmpty()) { openBedrockHome(p); return; }
            p.sendMessage(plugin.prefix()+Text.color("&7Searching HeadDB for &f"+q+"&7..."));
            headDB.getHeadApi().searchByName(q).thenAccept(heads -> Bukkit.getScheduler().runTask(plugin,
                    () -> openBedrockResults(p, heads, 0, "Search: "+q, () -> openBedrockSearch(p))));
        });
    }

    private void loadBedrockCategory(Player p, String category) {
        p.sendMessage(plugin.prefix()+Text.color("&7Loading &f"+pretty(category)+" &7heads..."));
        headDB.getHeadApi().findByCategory(category).thenAccept(heads -> Bukkit.getScheduler().runTask(plugin,
                () -> openBedrockResults(p, heads, 0, pretty(category), () -> openBedrockHome(p))));
    }

    private void openBedrockResults(Player p, List<Head> input, int requestedPage, String label, Runnable back) {
        if (!p.isOnline()) return;
        List<Head> heads = new ArrayList<>(input);
        heads.sort(Comparator.comparing(Head::getName, String.CASE_INSENSITIVE_ORDER));
        if (heads.isEmpty()) {
            List<BedrockForms.FormButton> empty = List.of(
                    new BedrockForms.FormButton("Search Again", () -> openBedrockSearch(p)),
                    new BedrockForms.FormButton("Back", back));
            BedrockForms.sendSimple(plugin, p, "HeadDB", "No heads found for " + label + ".", empty);
            return;
        }
        int pages = Math.max(1, (heads.size()+BEDROCK_PAGE_SIZE-1)/BEDROCK_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages-1));
        int start = page*BEDROCK_PAGE_SIZE, end = Math.min(start+BEDROCK_PAGE_SIZE, heads.size());
        List<BedrockForms.FormButton> buttons = new ArrayList<>();
        for (int i=start;i<end;i++) {
            Head h = heads.get(i);
            buttons.add(new BedrockForms.FormButton(h.getName()+" (#"+h.getId()+")", textureUrl(h), () -> confirmBedrockPurchase(p, h, heads, page, label, back)));
        }
        if (page > 0) buttons.add(new BedrockForms.FormButton("< Previous Page", () -> openBedrockResults(p, heads, page-1, label, back)));
        if (page < pages-1) buttons.add(new BedrockForms.FormButton("Next Page >", () -> openBedrockResults(p, heads, page+1, label, back)));
        buttons.add(new BedrockForms.FormButton("New Search", () -> openBedrockSearch(p)));
        buttons.add(new BedrockForms.FormButton("Back to Categories", back));
        String content = label + "\nPage " + (page+1) + "/" + pages + " • " + heads.size() + " heads"
                + "\nHead Tokens: " + tokens.countAvailableTokens(p.getUniqueId(),TokenType.HEADDB)
                + "\nChoose a named head to redeem.";
        BedrockForms.sendSimple(plugin, p, "HeadDB", content, buttons);
    }

    private void confirmBedrockPurchase(Player p, Head head, List<Head> results, int page, String label, Runnable back) {
        List<BedrockForms.FormButton> buttons = List.of(
                new BedrockForms.FormButton("Redeem for 1 Head Token", () -> purchaseBedrock(p, head)),
                new BedrockForms.FormButton("Back to Results", () -> openBedrockResults(p, results, page, label, back))
        );
        String tags = head.getTags()==null || head.getTags().isEmpty() ? "" : "\nTags: " + String.join(", ", head.getTags());
        BedrockForms.sendSimple(plugin, p, "Confirm Head", head.getName()+"\nID: #"+head.getId()+"\nCategory: "+pretty(head.getCategory())+tags
                +"\n\nCost: 1 HeadDB Token", buttons);
    }

    private void purchaseBedrock(Player p, Head head) {
        purchase(p, head);
        // purchase() historically re-opens page 0; on Bedrock return to the form home instead.
        if (p.isOnline() && tokens.countAvailableTokens(p.getUniqueId(),TokenType.HEADDB)>0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) openBedrockHome(p); }, 2L);
        }
    }


    private String textureUrl(Head head) {
        try {
            String texture = head.getTexture();
            if (texture == null || texture.isBlank()) return null;
            texture = texture.trim();
            if (texture.startsWith("http://") || texture.startsWith("https://")) return texture;
            if (texture.matches("[A-Fa-f0-9]{32,128}")) return "https://textures.minecraft.net/texture/" + texture;
            String decoded = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("https?://textures\\.minecraft\\.net/texture/[A-Za-z0-9_-]+").matcher(decoded.replace("\\/", "/"));
            return matcher.find() ? matcher.group() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String pretty(String value) {
        if (value == null || value.isBlank()) return "Other";
        String[] parts = value.replace('_',' ').replace('-',' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private void openLoaded(Player p,List<Head> heads,int requested) {
        if(!p.isOnline()) return;
        int pages=Math.max(1,(heads.size()+PAGE_SIZE-1)/PAGE_SIZE); int page=Math.max(0,Math.min(requested,pages-1));
        int start=page*PAGE_SIZE,end=Math.min(start+PAGE_SIZE,heads.size());
        List<Integer> ids=new ArrayList<>();
        for(int i=start;i<end;i++)ids.add(heads.get(i).getId());
        Inventory inv=Bukkit.createInventory(new HeadBrowserHolder(page,ids),54,Text.color("&2HeadDB &8• &fPage "+(page+1)+"/"+pages));
        for(int i=start;i<end;i++){
            Head h=heads.get(i); ItemStack item=h.getItem().clone(); ItemMeta meta=item.getItemMeta();
            if(meta!=null){List<String> lore=meta.hasLore()&&meta.getLore()!=null?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add("");lore.add(Text.color("&aCost: &f1 HeadDB Token"));lore.add(Text.color("&eClick to redeem"));meta.setLore(lore);item.setItemMeta(meta);} inv.setItem(i-start,item);
        }
        if(page>0) inv.setItem(45,button(Material.ARROW,"&ePrevious Page"));
        inv.setItem(47,button(Material.COMPASS,"&bSearch on headdb.net", "&7Click for a website link in chat", "&7Then use /headredeem <ID>"));
        inv.setItem(49,button(Material.CHEST,"&6Token Mailbox &7("+tokens.countAvailableTokens(p.getUniqueId(),TokenType.HEADDB)+" HeadDB)"));
        if(page<pages-1) inv.setItem(53,button(Material.ARROW,"&eNext Page"));
        p.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof HeadBrowserHolder holder))return; event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p))return;
        if (!p.hasPermission("vipbridge.heads.search")) { p.closeInventory(); return; }
        int slot=event.getRawSlot();
        if(slot==47){p.closeInventory();sendWebsite(p);return;} if(slot==45){open(p,holder.page()-1);return;} if(slot==53){open(p,holder.page()+1);return;} if(slot==49){Bukkit.dispatchCommand(p,"tokens");return;}
        if(slot<0||slot>=holder.headIds().size())return;
        if (!p.hasPermission("vipbridge.heads.redeem")) return;
        int id=holder.headIds().get(slot);
        if(!available())return;
        redeemById(p, id);
    }
    @EventHandler public void onDrag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof HeadBrowserHolder)e.setCancelled(true);}

    private void purchase(Player p,Head head){
        if (!p.isOnline() || !p.hasPermission("vipbridge.heads.redeem")) return;
        ItemStack prepared;
        try {
            prepared = head.getItem().clone();
            if (prepared.getType().isAir()) throw new IllegalStateException("Empty head item");
        } catch (Exception ex) {
            p.sendMessage(plugin.prefix()+Text.color("&cCould not create that head. No token used."));
            return;
        }
        String token=tokens.consumeOne(p.getUniqueId(),TokenType.HEADDB,"headdb:"+head.getId());
        if(token==null){decline(p);p.closeInventory();return;}
        ItemStack item=prepared; var leftover=p.getInventory().addItem(item);
        if(!leftover.isEmpty()){
            for(ItemStack left:leftover.values()) itemMailbox.addItem(p.getUniqueId(),p.getName(),left,"headdb-token");
            p.sendMessage(plugin.prefix()+Text.color("&eYour inventory was full, so the head was sent to your Deliveries."));
        } else p.sendMessage(plugin.prefix()+Text.color("&aRedeemed 1 HeadDB Token for &e"+head.getName()+"&a."));
        p.sendMessage(plugin.prefix()+Text.color("&7Token used: &e1 HeadDB Token &8• &7Remaining: &e"+tokens.countAvailableTokens(p.getUniqueId(),TokenType.HEADDB)));
        if(tokens.countAvailableTokens(p.getUniqueId(),TokenType.HEADDB)>0) {
            if (!BedrockLinkForm.isBedrock(p, plugin)) open(p,0);
        } else p.closeInventory();
    }
    private ItemStack button(Material m, String n, String... description) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(n));
            if (description.length > 0) meta.setLore(java.util.Arrays.stream(description).map(Text::color).toList());
            i.setItemMeta(meta);
        }
        return i;
    }
}
