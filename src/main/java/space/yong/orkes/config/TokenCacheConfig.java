package space.yong.orkes.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.Nonnull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TokenCacheConfig {
    @Bean
    public Cache<String, CachedToken> tokenCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfter(new Expiry<String, CachedToken>() {
                @Override
                public long expireAfterCreate(@Nonnull String key, @Nonnull CachedToken value, long currentTime) {
                    long nanosLeft = java.time.Duration.between(java.time.Instant.now(), value.expiresAt()).toNanos();
                    return Math.max(0L, nanosLeft);
                }
                @Override
                public long expireAfterUpdate(@Nonnull String key, @Nonnull CachedToken value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
                @Override
                public long expireAfterRead(@Nonnull String key, @Nonnull CachedToken value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();
    }

    public record CachedToken(String email, java.time.Instant expiresAt) {}
}
