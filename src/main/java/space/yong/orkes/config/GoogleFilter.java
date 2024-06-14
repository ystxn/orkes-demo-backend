package space.yong.orkes.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Slf4j
@Service
public class GoogleFilter extends OncePerRequestFilter {
    @Value("${google.client-id}")
    private String clientId;

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain chain
    ) throws ServletException, IOException {
        log.debug("Entered GoogleFilter with clientId: {}", clientId);
        final String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            log.debug("Bearer exists");
            try {
                String token = authHeader.substring(7);
                var verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                    .setAudience(List.of(clientId))
                    .build();
                log.debug("Verifier built");
                GoogleIdToken idToken = GoogleIdToken.parse(verifier.getJsonFactory(), token);
                log.debug("Token parsed");
                verifier.verify(idToken);
                log.debug("Token verified");
                GoogleIdToken.Payload payload = idToken.getPayload();
                log.debug("Payload extracted");
                var authToken = new UsernamePasswordAuthenticationToken(payload.getEmail(), null, null);
                log.debug("Auth token created");
                SecurityContextHolder.getContext().setAuthentication(authToken);
                log.debug("Security context set");
            } catch (Exception e) {
                log.error("Exception thrown", e);
                response.setStatus(401);
            }
            log.debug("End of GoogleFilter");
        }
        chain.doFilter(request, response);
    }
}
