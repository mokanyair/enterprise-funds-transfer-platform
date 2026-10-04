package com.enterprise.funds.transfer.security;

import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.web.ErrorFactory;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.Arrays;
import java.util.List;

/**
 * Stateless resource server: every /api call needs a valid bearer token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

        @Bean
        CorsConfigurationSource corsConfigurationSource(
                        @Value("${FUNDS_CORS_ALLOWED_ORIGINS:http://localhost:5173}") String allowedOrigins) {

                CorsConfiguration config = new CorsConfiguration();

                config.setAllowedOrigins(
                                Arrays.stream(allowedOrigins.split(","))
                                                .map(String::trim)
                                                .filter(s -> !s.isEmpty())
                                                .toList());

                config.setAllowedMethods(
                                List.of("GET", "POST", "OPTIONS"));

                config.setAllowedHeaders(
                                List.of(
                                                "Authorization",
                                                "Content-Type",
                                                "Idempotency-Key",
                                                "X-Correlation-Id"));

                config.setExposedHeaders(
                                List.of(
                                                "Location",
                                                "X-Correlation-Id",
                                                "Idempotent-Replayed"));

                config.setAllowCredentials(false);
                config.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                source.registerCorsConfiguration("/api/**", config);

                return source;
        }

        @Bean
        SecurityFilterChain filterChain(
                        HttpSecurity http,
                        ErrorFactory errors,
                        CorsConfigurationSource corsConfigurationSource)
                        throws Exception {

                AuthenticationEntryPoint unauthenticated = (request, response, ex) -> errors.write(
                                response,
                                new ApiException(ErrorCode.UNAUTHENTICATED)
                                                .header("WWW-Authenticate", "Bearer"));

                AccessDeniedHandler forbidden = (request, response, ex) -> errors.write(
                                response,
                                new ApiException(ErrorCode.FORBIDDEN));

                http
                                .csrf(AbstractHttpConfigurer::disable)
                                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                                .sessionManagement(s -> s.sessionCreationPolicy(
                                                SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(a -> a
                                                .requestMatchers(
                                                                "/actuator/health/**",
                                                                "/actuator/health")
                                                .permitAll()
                                                .anyRequest()
                                                .authenticated())
                                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                                                .authenticationEntryPoint(unauthenticated))
                                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated)
                                                .accessDeniedHandler(forbidden));

                return http.build();
        }
}