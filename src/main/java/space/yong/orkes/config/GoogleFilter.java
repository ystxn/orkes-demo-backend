package space.yong.orkes.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import com.github.benmanes.caffeine.cache.Cache;

@Service
public class GoogleFilter extends OncePerRequestFilter {
    @Value("${google.client-id}")
    private String clientId;

    private final Cache<String, TokenCacheConfig.CachedToken> tokenCache;

    public GoogleFilter(Cache<String, TokenCacheConfig.CachedToken> tokenCache) {
        this.tokenCache = tokenCache;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain chain
    ) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            final String token = authHeader.substring(7);
            try {
                var cached = tokenCache.getIfPresent(token);
                if (cached == null) {
                    var verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                        .setAudience(List.of(clientId))
                        .build();
                    GoogleIdToken idToken = GoogleIdToken.parse(verifier.getJsonFactory(), token);

                    if (!verifier.verify(idToken)) {
                        throw new SecurityException("Invalid Google ID token");
                    }

                    GoogleIdToken.Payload payload = idToken.getPayload();
                    String email = payload.getEmail();
                    long expSeconds = payload.getExpirationTimeSeconds();
                    var expiresAt = java.time.Instant.ofEpochSecond(expSeconds);

                    var value = new TokenCacheConfig.CachedToken(email, expiresAt);
                    tokenCache.put(token, value);
                    cached = value;
                }
                var authToken = new UsernamePasswordAuthenticationToken(cached.email(), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (Exception e) {
                response.setStatus(401);
            }
        }
        chain.doFilter(request, response);
    }
}
