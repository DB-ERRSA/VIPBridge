package com.errsa.vipbridge.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public final class ClickableMessages {
    private ClickableMessages() {}
    private static Component prefix(){return Component.text("Rewards » ",NamedTextColor.GOLD);}

    public static void sendMailboxReminder(Player player,int itemCount){
        player.sendMessage(prefix().append(Component.text("You have ",NamedTextColor.WHITE)).append(Component.text(itemCount,NamedTextColor.YELLOW))
                .append(Component.text(itemCount==1?" item":" items",NamedTextColor.WHITE)).append(Component.text(" waiting in Deliveries. ",NamedTextColor.WHITE))
                .append(Component.text("[Open]",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/tokens")).hoverEvent(HoverEvent.showText(Component.text("Open Token Mailbox and deliveries")))));
    }
    public static void sendMailboxNewItem(Player player,int itemCount){
        player.sendMessage(prefix().append(Component.text("A new item is waiting in Deliveries. ",NamedTextColor.WHITE))
                .append(Component.text("[Open]",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/tokens")))
                .append(Component.text("  Waiting: ",NamedTextColor.GRAY)).append(Component.text(itemCount,NamedTextColor.YELLOW)));
    }
    public static void sendTokenAdded(Player player,String type,int amount,int total){
        player.sendMessage(prefix().append(Component.text("+"+amount+" "+type+" Token"+(amount==1?"":"s")+" added to your Token Mailbox. ",NamedTextColor.GREEN))
                .append(Component.text("[Open]",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/tokens")))
                .append(Component.text("  Total: "+total,NamedTextColor.GRAY)));
    }
    public static void sendVipWelcome(Player player,int maps,int music,int catchphrases,int heads){
        int total=maps+music+catchphrases+heads;
        player.sendMessage(prefix().append(Component.text("Welcome to VIP! ",NamedTextColor.GOLD))
                .append(Component.text("Your reward package is ready.",NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  Tokens: ",NamedTextColor.GRAY)
                .append(Component.text(total,NamedTextColor.YELLOW))
                .append(Component.text(" total  •  Maps "+maps+"  •  Music "+music+"  •  Catchphrases "+catchphrases+"  •  Heads "+heads,NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  ",NamedTextColor.GRAY)
                .append(Component.text("[Open Token Mailbox]",NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/tokens"))
                        .hoverEvent(HoverEvent.showText(Component.text("View and redeem your VIP tokens"))))
                .append(Component.text("   ",NamedTextColor.GRAY))
                .append(Component.text("[Token Shop]",NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/tokenshop"))
                        .hoverEvent(HoverEvent.showText(Component.text("Buy additional tokens")))));
    }

    public static void sendTokenReminder(Player player,int total,int maps,int music,int catchphrases,int heads){
        Component msg=prefix().append(Component.text("You have ",NamedTextColor.WHITE)).append(Component.text(total,NamedTextColor.YELLOW))
                .append(Component.text(" token"+(total==1?"":"s")+" ready to use",NamedTextColor.WHITE));
        player.sendMessage(msg);
        player.sendMessage(Component.text("  Maps "+maps+"  •  Music "+music+"  •  Catchphrases "+catchphrases+"  •  Heads "+heads,NamedTextColor.GRAY)
                .append(Component.text("   [Open Token Mailbox]",NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/tokens"))));
    }
    /** PlayerQuests-style bank receipt for a successful token purchase. */
    public static void sendPurchaseTransaction(Player player,String tokenName,String before,String after,String price,int tokensBefore,int tokensAfter){
        player.sendMessage(prefix().append(Component.text("Transaction: ",NamedTextColor.GOLD))
                .append(Component.text("Purchased "+tokenName,NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  Bank: ",NamedTextColor.GRAY)
                .append(Component.text(before,NamedTextColor.WHITE))
                .append(Component.text(" → ",NamedTextColor.DARK_GRAY))
                .append(Component.text(after,NamedTextColor.GREEN))
                .append(Component.text(" (-"+price+")",NamedTextColor.GRAY)));
        player.sendMessage(Component.text("  Token Mailbox: ",NamedTextColor.GRAY)
                .append(Component.text(tokensBefore,NamedTextColor.WHITE))
                .append(Component.text(" → ",NamedTextColor.DARK_GRAY))
                .append(Component.text(tokensAfter,NamedTextColor.YELLOW))
                .append(Component.text(" (+1)   ",NamedTextColor.GREEN))
                .append(Component.text("[Open]",NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/tokens"))
                        .hoverEvent(HoverEvent.showText(Component.text("Open Token Mailbox")))));
    }

    /** Clear declined-purchase receipt. Balance details are included when available. */
    public static void sendPurchaseDeclined(Player player,String reason,String balance,String price,String shortBy){
        player.sendMessage(prefix().append(Component.text("Transaction declined: ",NamedTextColor.RED))
                .append(Component.text(reason,NamedTextColor.WHITE)));
        if(balance!=null && price!=null){
            Component line=Component.text("  Bank: ",NamedTextColor.GRAY)
                    .append(Component.text(balance,NamedTextColor.WHITE))
                    .append(Component.text("  •  Price: ",NamedTextColor.DARK_GRAY))
                    .append(Component.text(price,NamedTextColor.YELLOW));
            if(shortBy!=null) line=line.append(Component.text("  •  Short: ",NamedTextColor.DARK_GRAY)).append(Component.text(shortBy,NamedTextColor.RED));
            player.sendMessage(line);
        }
        player.sendMessage(Component.text("  No money was taken and no token was added.",NamedTextColor.GRAY));
    }

    /** Java-only website prompt with a true OPEN_URL click event. */
    public static void sendExternalFormLink(Player player,String url,String tokenId){
        player.sendMessage(Component.text("  ",NamedTextColor.GRAY)
                .append(Component.text("[OPEN WEBSITE]",NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open submission website")))));
        player.sendMessage(Component.text("  Token ID: ",NamedTextColor.GRAY)
                .append(Component.text(tokenId,NamedTextColor.YELLOW)));
    }

}
