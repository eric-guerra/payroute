package com.ericguerra.payroute.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@SpringBootApplication
@RestController
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(a -> a
                        .pathMatchers("/webhooks/stripe").permitAll() // authenticated by Stripe signature downstream
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers("/v1/payments/**").hasAuthority("SCOPE_payments")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }

    /** Rate-limit per merchant (from the token), not per IP: merchants sit behind shared NATs. */
    @Bean
    KeyResolver merchantKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(p -> p instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken t
                        ? String.valueOf(((Jwt) t.getPrincipal()).getClaims().getOrDefault("merchant_id", t.getName()))
                        : p.getName())
                .defaultIfEmpty("anonymous");
    }

    @RequestMapping("/fallback/payments")
    public Mono<Map<String, String>> fallback() {
        return Mono.just(Map.of("error", "payments temporarily unavailable; retry with the same Idempotency-Key"));
    }

    /** Mutating payment calls must carry an Idempotency-Key (8–64 safe chars). */
    @Component
    public static class RequireIdempotencyKeyGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {
        public RequireIdempotencyKeyGatewayFilterFactory() { super(Object.class); }

        @Override
        public GatewayFilter apply(Object config) {
            return (exchange, chain) -> {
                var req = exchange.getRequest();
                if (req.getMethod() == HttpMethod.POST) {
                    String key = req.getHeaders().getFirst("Idempotency-Key");
                    if (key == null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
                        var res = exchange.getResponse();
                        res.setStatusCode(HttpStatus.BAD_REQUEST);
                        res.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        var body = res.bufferFactory().wrap("{\"error\":\"Idempotency-Key header required\"}".getBytes());
                        return res.writeWith(Mono.just(body));
                    }
                }
                return chain.filter(exchange);
            };
        }
    }
}
