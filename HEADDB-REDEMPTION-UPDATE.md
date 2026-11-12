# HeadDB / VIPBridge source update

## New player commands
- `/headsearch` — clickable web catalog URL in Minecraft chat.
- `/headredeem 122283` — look up stable numeric ID in locally loaded HeadDB and exchange **one HeadDB token**. If unavailable or the player has no tokens, no token is consumed. This head ID may not be in your currently synced catalog.

## VIPBridge head browser
- Compass in slot 47 opens a clickable website link in chat and instructions to redeem by ID.
- Successful redemption prints the one-token deduction and remaining HeadDB token count.
- Zero tokens prints a declined transaction with clickable `/tokenshop` link.

## Security / integration
- **Do not grant** `headdb.command.give` to regular players. It bypasses VIPBridge token accounting.
- The native `/hdb` take-head action is not intercepted. Only allow native browsing when you have separately blocked the give/take paths in HeadDB or made them staff-only. Standard plugin `/hdb search` website hint can be enabled using `website.searchHint.enabled: true` in plugins/HeadDB/config.yml.
- VIPBridge still owns the item rewards, including inventory overflow delivery.

## Build
Run `./gradlew build` on a machine with internet/dependencies. This sandbox could not download the Gradle wrapper distribution, so the modified source has **not been compiled or server-tested**. Backup `plugins/VIPBridge/` and token records before replacing any built JAR. The bundled libs include HeadDB 6.0.3; confirm compatibility with the new 6.1.1 server plugin during build/runtime.
