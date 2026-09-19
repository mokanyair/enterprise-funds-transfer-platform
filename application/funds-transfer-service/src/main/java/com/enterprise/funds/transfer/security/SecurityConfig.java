package com.enterprise.funds.transfer.security;

import com.enterprise.funds.transfer.domain.ApiException;
import com.enterprise.funds.transfer.domain.ErrorCode;
import com.enterprise.funds.transfer.web.ErrorFactory;
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

/**
 * Stateless resource server: every /api call needs a valid bearer token. Failures use the standard error body and,
 * because CorrelationIdFilter runs first, still carry X-Correlation-Id.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ErrorFactory errors) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, ex) ->
                errors.write(response, new ApiException(ErrorCode.UNAUTHENTICATED).header("WWW-Authenticate", "Bearer"));
        AccessDeniedHandler forbidden = (request, response, ex) ->
                errors.write(response, new ApiException(ErrorCode.FORBIDDEN));

        http.csrf(AbstractHttpConfigurer::disable) // stateless bearer API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthenticated))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden));
        return http.build();
    }
}
