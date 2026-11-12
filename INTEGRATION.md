# VIPBridge Reward Token Integration

VIPBridge now exposes virtual reward tokens to other plugins. Tokens are stored in `tokens.yml`; they do **not** enter the player's inventory and therefore are not affected by PerWorldInventory or world switching.

## Console / command integration

These can be dispatched by any plugin as console commands:

```text
vip token give <player> map [amount]
vip token give <player> music [amount]
vip token give <player> catchphrase [amount]
vip token give <player> head [amount]
```

Examples:

```text
vip token give DaBeans24 head 1
vip token give DaBeans24 music 2
vip token give DaBeans24 map 1
vip token give DaBeans24 catchphrase 1
```

This is the simplest integration for a rank-progression plugin: when a rank reward fires, dispatch one of those commands from console.

## Java API integration

Add the VIPBridge jar as `compileOnly` in the other plugin and add `VIPBridge` as a `softdepend` or `depend` in that plugin's `plugin.yml`.

```java
import com.errsa.vipbridge.api.VIPBridgeAPI;
import com.errsa.vipbridge.tokens.TokenType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

VIPBridgeAPI rewards = Bukkit.getServicesManager().load(VIPBridgeAPI.class);
if (rewards != null) {
    rewards.giveTokens(player, TokenType.HEADDB, 1);
}
```

Available `TokenType` values:

```text
CUSTOM_MAP
CUSTOM_MUSIC
CATCHPHRASE
HEADDB
```

Public API methods:

```java
List<String> giveTokens(OfflinePlayer player, TokenType type, int amount)
List<String> giveTokens(UUID uuid, TokenType type, int amount)
int getAvailableTokens(UUID uuid, TokenType type)
boolean hasToken(UUID uuid, TokenType type)
String consumeToken(UUID uuid, TokenType type, String source)
```

Normally other plugins should call `giveTokens(...)`. `consumeToken(...)` is available for integrations that provide their own redemption UI.

## Player commands

```text
/vip        - VIP guide
/tokens     - open Token Mailbox
/tokenshop  - open Token Shop
/vip heads  - open HeadDB token browser
/vip mailbox - open delivered Item Mailbox
```

Duplicate player aliases were intentionally removed. Staff tools remain under `/vip` and require `vipbridge.admin`.

## HeadDB behavior

- 1 HeadDB Token purchases exactly 1 HeadDB head.
- `/vip heads` opens VIPBridge's own paginated HeadDB browser.
- It uses the HeadDB API directly, rather than HeadDB's Vault price.
- The token is consumed only after the player actually chooses a head.
- If the player's inventory is full, the purchased head is sent to the Item Mailbox.

## Upgrade behavior

On login, legacy physical token items from older VIPBridge builds are removed after their matching virtual token records are found. The actual token entitlement stays in `tokens.yml` and appears in the Token Mailbox.

## Player Token Shop transaction UX

Players can now use a single no-argument command:

- `/tokenshop` — opens the Token Shop GUI and lets the player click the token type they want.
- `/tokenshop` — alias for `/tokenshop`.
- `/tokenshop` — remains available for backwards compatibility.

Successful purchases show a PlayerQuests-style bank receipt with before/after bank balance and before/after token balance. A successful purchase plays `ENTITY_EXPERIENCE_ORB_PICKUP` by default. An insufficient-funds/failed purchase plays `ENTITY_ENDERMAN_TELEPORT` by default. Both sounds are configurable under `sounds.token-purchase-success` and `sounds.token-purchase-failure`.


### Catchphrase shop rule
Catchphrase Tokens are intentionally not sold in the public Token Shop. They remain part of the VIP package and can still be granted/managed by staff or integrations when needed.
