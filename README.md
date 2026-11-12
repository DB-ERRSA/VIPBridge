# VIPBridge

A Paper VIP reward system with virtual tokens, redemption, staff fulfillment, item delivery mailboxes, and integrations for custom maps, music, decorative heads and VIP displays.

| Component | Contents |
|---|---|
| VIP rewards | Configured grants, promotion, welcome guide, announcements |
| Virtual tokens | Map, music, catchphrase, and HeadDB tokens stored outside player inventories |
| Token shop | Vault-backed purchases for enabled token types |
| Redemption | Token mailbox, submission forms, reward processing, delivery history |
| Displays | FancyNpcs / FancyHolograms VIP statues and catchphrases |
| Integrations | ImageFrame, HeadDB, URLCustomDiscs, LuckPerms, Vault, optional API for other plugins |

## Reference

- [Commands and permissions](docs/COMMANDS.md)
- [Tokens and fulfillment](docs/TOKENS.md)
- [Configuration](docs/CONFIG.md)
- [Integrations and API](docs/INTEGRATIONS.md)
- [Builds](docs/BUILDS.md)

## Requirements

Paper 1.21.4 / Java 21. The current `plugin.yml` requires FancyNpcs, FancyHolograms and ImageFrame at startup. Other integrations are feature-specific, including Vault for purchases and HeadDB for heads. Music workflows reference URLCustomDiscs.

## Build

```powershell
.\gradlew build
```

Released downloads: [GitHub Releases](https://github.com/DB-ERRSA/VIPBridge/releases).
