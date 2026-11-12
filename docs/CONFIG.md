## Configuration reference

The sections below are from the bundled `config.yml`.

| Section | Controls |
|---|---|
| `vip` | Promotion command, rewards, duplicate prevention, rank reset. |
| `forms` | Submission URLs and messages for custom rewards. |
| `tokens` | Legacy token appearance/migration. |
| `client-detection` | Bedrock naming prefix. |
| `client-support` | Java-only token GUI behavior and Bedrock messages. |
| `token-mailbox` | Token description text. |
| `shop` | Enablement, prices and labels. |
| `mailbox` | Delivered-item storage/UI. |
| `onboarding` | VIP guide and first-join messages. |
| `shoutouts` | Announcement text, sounds and boss bar. |
| `sounds` | Token purchase feedback. |
| `music` | URLCustomDiscs processing and download controls. |
| `maps` | ImageFrame dimensions and accepted image formats. |

The installed defaults are in [`src/main/resources/config.yml`](../src/main/resources/config.yml).

## Site-specific defaults

The bundled `forms.*.url` values point to ERRSA request forms, `vip.luckperms-command` uses a `main` track, and `music.url-custom-discs.audio-folder` points to a local URLCustomDiscs path. These are configurable integrations, not generic defaults for other servers. Change them before a public example configuration is published.

`/vip reload` handles reloadable reward/shop and message settings; the bundled comments explicitly permit shop price changes followed by this command.
