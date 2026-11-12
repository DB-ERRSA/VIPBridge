# Integrations and API

## Plugin dependencies

| Plugin | Use |
|---|---|
| FancyNpcs, FancyHolograms | Required by current plugin descriptor; VIP statues and displays |
| ImageFrame | Required by current plugin descriptor; custom maps |
| LuckPerms | Optional rank promotion and reset commands |
| Vault | Token shop economy transactions |
| HeadDB | Decorative-head token redemption |
| URLCustomDiscs | Custom music processing; configured under `music.url-custom-discs` |
| Floodgate | Client support / Bedrock detection |
| PlaceholderAPI, Essentials | Optional integrations |

## Grant tokens from another plugin

These commands can be dispatched by the console:

```text
vip token give <player> map [amount]
vip token give <player> music [amount]
vip token give <player> catchphrase [amount]
vip token give <player> head [amount]
```

## Java API

VIPBridge registers `com.errsa.vipbridge.api.VIPBridgeAPI` through Bukkit ServicesManager. Other plugins can load it and issue tokens without depending on console commands.

```java
VIPBridgeAPI api = Bukkit.getServicesManager().load(VIPBridgeAPI.class);
if (api != null) {
    api.giveTokens(player, TokenType.HEADDB, 1);
}
```

| API method | Purpose |
|---|---|
| `giveTokens(OfflinePlayer, TokenType, int)` | Grant tokens to a player |
| `giveTokens(UUID, TokenType, int)` | Grant by UUID |
| `getAvailableTokens(UUID, TokenType)` | Query token count |
| `hasToken(UUID, TokenType)` | Check entitlement |
| `consumeToken(UUID, TokenType, String)` | Redeem token in an external integration |

`TokenType` values: `CUSTOM_MAP`, `CUSTOM_MUSIC`, `CATCHPHRASE`, `HEADDB`.

Use a `compileOnly` dependency on VIPBridge and declare a plugin `softdepend` or `depend` when integrating.
