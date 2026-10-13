package com.mixinfer.auth;

import com.mixinfer.config.MixInferProperties;
import com.mixinfer.ratelimit.RateLimitPolicy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves a client-facing authentication secret to a {@link VirtualKey}.
 *
 * <p>Supports two configuration forms:
 * <ul>
 *   <li><b>V0.4+</b> {@code mixinfer.virtual-keys}: each entry must declare
 *       an explicit {@code id}.</li>
 *   <li><b>V0.1-V0.3</b> {@code mixinfer.api-keys}: each entry is upgraded
 *       to a VirtualKey whose id is deterministically derived from the
 *       normalized name. Renaming such a key produces a new identity.</li>
 * </ul>
 *
 * <p>When both forms are present, they are merged. Duplicate secrets cause
 * a startup failure, since they would make authentication ambiguous.
 */
@Component
public class VirtualKeyRegistry {

    private final Map<String, VirtualKey> keysBySecret;
    private final Map<String, VirtualKey> keysById;

    public VirtualKeyRegistry(MixInferProperties properties) {
        Map<String, VirtualKey> registry = new HashMap<>();

        for (MixInferProperties.VirtualKeyConfig cfg : properties.getVirtualKeys()) {
            if (cfg.getId() == null || cfg.getId().isBlank()) {
                throw new IllegalStateException(
                        "virtual-keys entry is missing required 'id': " + cfg.getName());
            }
            if (cfg.getKey() == null || cfg.getKey().isBlank()) {
                throw new IllegalStateException(
                        "virtual-keys entry has empty 'key': " + cfg.getId());
            }
            VirtualKey vk = VirtualKey.builder()
                    .id(cfg.getId())
                    .name(cfg.getName())
                    .key(cfg.getKey())
                    .rateLimit(toPolicy(cfg.getRateLimit()))
                    .build();
            register(registry, vk);
        }

        for (MixInferProperties.ApiKeyConfig cfg : properties.getApiKeys()) {
            if (cfg.getKey() == null || cfg.getKey().isBlank()) {
                continue;
            }
            VirtualKey vk = VirtualKey.builder()
                    .id(deriveLegacyId(cfg.getName()))
                    .name(cfg.getName())
                    .key(cfg.getKey())
                    .rateLimit(null)
                    .build();
            register(registry, vk);
        }

        this.keysBySecret = Map.copyOf(registry);
        this.keysById = keysBySecret.values().stream()
                .collect(Collectors.toUnmodifiableMap(VirtualKey::getId, v -> v));
    }

    /** Returns the VirtualKey for the given secret, if any. */
    public Optional<VirtualKey> findBySecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(keysBySecret.get(secret));
    }

    /** Returns the VirtualKey for the given id, if any. */
    public Optional<VirtualKey> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(keysById.get(id));
    }

    private void register(Map<String, VirtualKey> registry, VirtualKey vk) {
        VirtualKey previous = registry.put(vk.getKey(), vk);
        if (previous != null) {
            throw new IllegalStateException(
                    "duplicate virtual key secret for ids '"
                            + previous.getId() + "' and '" + vk.getId() + "'");
        }
    }

    private RateLimitPolicy toPolicy(MixInferProperties.RateLimitPolicyConfig cfg) {
        if (cfg == null || cfg.getRequestsPerMinute() <= 0) {
            return null;
        }
        return RateLimitPolicy.perMinute(cfg.getRequestsPerMinute());
    }

    /**
     * Deterministic id for a legacy api-key: SHA-256 of the normalized name,
     * first 12 hex characters, prefixed with {@code legacy-}.
     *
     * <p>Renaming a legacy key changes its id; this is documented in
     * ADR-004 and the user is expected to migrate to the new format if
     * stable ids matter.
     */
    private static String deriveLegacyId(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash, 0, 6);
            return "legacy-" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}