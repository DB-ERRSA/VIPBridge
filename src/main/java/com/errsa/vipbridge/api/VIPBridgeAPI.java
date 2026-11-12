package com.errsa.vipbridge.api;

import com.errsa.vipbridge.tokens.TokenService;
import com.errsa.vipbridge.tokens.TokenType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;

/** Public API for progression plugins and other server systems. */
public final class VIPBridgeAPI {
    private final TokenService tokens;

    public VIPBridgeAPI(TokenService tokens) {
        this.tokens = tokens;
    }

    public List<String> giveTokens(OfflinePlayer player, TokenType type, int amount) {
        if (player == null || type == null || amount < 1) throw new IllegalArgumentException("player/type/amount");
        return tokens.createTokens(player, type, amount);
    }

    public List<String> giveTokens(UUID uuid, TokenType type, int amount) {
        return giveTokens(Bukkit.getOfflinePlayer(uuid), type, amount);
    }

    public int getAvailableTokens(UUID uuid, TokenType type) {
        return tokens.countAvailableTokens(uuid, type);
    }

    /** Consumes one available token and returns its token id, or null when none exist. */
    public String consumeToken(UUID uuid, TokenType type, String source) {
        return tokens.consumeOne(uuid, type, source);
    }

    public boolean hasToken(UUID uuid, TokenType type) {
        return getAvailableTokens(uuid, type) > 0;
    }
}
