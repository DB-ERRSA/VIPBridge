# Tokens and reward fulfillment

Tokens are virtual entitlements stored in `tokens.yml`, not items held in an inventory. They persist across inventory/world switches.

| Token | Redemption |
|---|---|
| Custom map | Submit map request; ImageFrame is used for delivery |
| Custom music | Request processing through URLCustomDiscs integration |
| Catchphrase | Set/request VIP statue catchphrase |
| HeadDB | Choose one HeadDB head per token |

`/tokens` shows available tokens, redemptions and completed item deliveries. `/tokenshop` lists purchasable tokens; catchphrase tokens are not sold by the default public shop. New tokens are virtual; the code includes migration support for legacy physical tokens.

## Shop defaults

| Token | Price |
|---|---:|
| Custom map | 5,000 |
| Custom music | 7,500 |
| HeadDB | 1,000 |

The shop requires Vault and an economy provider. Prices are in `shop.prices`.

## Deliveries

Finished physical rewards are collected using the Deliveries menu. HeadDB purchases send the item to the mailbox if inventory space is unavailable.

## Client support

`client-support.java-only-token-guis` defaults to `true`: Java clients use the token GUIs; Bedrock clients receive the configured notice without spending saved tokens.
