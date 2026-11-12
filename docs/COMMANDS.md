# Commands and permissions

| Command | Behavior | Permission |
|---|---|---|
| `/vip` | VIP guide and reward information | `vipbridge.vip.use` |
| `/tokens` | Virtual token mailbox and redemption GUI | `vipbridge.tokens.use` |
| `/tokenshop` | Shop for enabled token types | `vipbridge.tokens.shop` |
| `/headsearch` | Open HeadDB catalog discovery | `vipbridge.heads.search` |
| `/headredeem <id>` | Redeem an eligible HeadDB item | `vipbridge.heads.redeem` |
| `/vip mailbox` | Open delivered-item mailbox | `vipbridge.vip.use` |
| `/vip heads` | VIPBridge HeadDB selector | `vipbridge.vip.use` |

`vipbridge.tokens.deliveries` (default true) controls protected deliveries. Public commands default to true. Staff commands under `/vip` require `vipbridge.admin` (default op).

## Staff command interface

`/vip` includes `reload`, `grant`, `token`, `mailbox`, `statue`, `catchphrase` and `player` operations. The implementation supplies subcommand options in its in-game help and tab completion; avoid using an undocumented argument order.

## Token grants from other plugins

```text
vip token give <player> map [amount]
vip token give <player> music [amount]
vip token give <player> catchphrase [amount]
vip token give <player> head [amount]
```

These are console-dispatchable commands for rank rewards and other integrations.
