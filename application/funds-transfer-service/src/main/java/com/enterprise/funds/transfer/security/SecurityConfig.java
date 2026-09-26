package com.enterprise.funds.transfer.security;

import com.enterprise.funds.transfer.config.FundsProperties;
import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.web.ErrorFactory;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless resource server: every /api call needs a valid bearer token. Failures use the standard error body and,
 * because CorrelationIdFilter runs first, still carry X-Correlation-Id.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http, ErrorFactory errors, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
            throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, ex) ->
                errors.write(response, new ApiException(ErrorCode.UNAUTHENTICATED).header("WWW-Authenticate", "Bearer"));
        AccessDeniedHandler forbidden = (request, response, ex) ->
                errors.write(response, new ApiException(ErrorCode.FORBIDDEN));

        http.csrf(AbstractHttpConfigurer::disable) // stateless bearer API, no cookies
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthenticated))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden));
        return http.build();
    }

    /**
     * No origin is allowed until funds.cors.allowed-origins (FUNDS_CORS_ALLOWED_ORIGINS, comma-separated) names
     * one: this is a browser SPA API with no cookies, so an empty allow-list is the safe default, not "*".
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(FundsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOriginsOrEmpty());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Location", "Idempotent-Replayed", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(java.time.Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
