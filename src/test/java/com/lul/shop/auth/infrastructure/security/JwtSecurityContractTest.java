package com.lul.shop.auth.infrastructure.security;

import com.lul.shop.auth.domain.User;
import com.lul.shop.auth.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtSecurityContractTest {

    private static final String SECRET =
            "test-jwt-secret-key-that-is-at-least-32-bytes";

    private static final UUID USER_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final Duration TOKEN_TTL =
            Duration.ofMinutes(15);

    @Test
    void shouldIssueDecodeAndConvertProductionJwtContract() {
        Instant now = Instant.now()
                .truncatedTo(ChronoUnit.SECONDS);

        JwtProperties properties = new JwtProperties(
                SECRET,
                TOKEN_TTL,
                "shop"
        );

        JwtConfig jwtConfig = new JwtConfig();
        JwtEncoder encoder = jwtConfig.jwtEncoder(properties);
        JwtDecoder decoder = jwtConfig.jwtDecoder(properties);

        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(
                properties,
                encoder,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        String token = issuer.createAccessToken(user());
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject())
                .isEqualTo(USER_ID.toString());
        assertThat(jwt.getClaimAsString("iss"))
                .isEqualTo("shop");
        assertThat(jwt.getClaimAsString("email"))
                .isEqualTo("customer@example.com");
        assertThat(jwt.getClaimAsStringList("roles"))
                .containsExactly("ADMIN", "USER");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt())
                .isEqualTo(now.plus(TOKEN_TTL));

        JwtAuthenticationConverter converter =
                jwtConfig.jwtAuthenticationConverter();

        var authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName())
                .isEqualTo(USER_ID.toString());

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_USER",
                        "ROLE_ADMIN"
                );
    }

    @Test
    void shouldRejectTokenWithUnexpectedIssuer() {
        Instant now = Instant.now()
                .truncatedTo(ChronoUnit.SECONDS);

        JwtProperties expectedProperties = new JwtProperties(
                SECRET,
                TOKEN_TTL,
                "shop"
        );

        JwtProperties unexpectedProperties = new JwtProperties(
                SECRET,
                TOKEN_TTL,
                "other-shop"
        );

        JwtConfig jwtConfig = new JwtConfig();

        JwtEncoder encoder =
                jwtConfig.jwtEncoder(expectedProperties);

        JwtDecoder decoder =
                jwtConfig.jwtDecoder(expectedProperties);

        JwtAccessTokenIssuer unexpectedIssuer =
                new JwtAccessTokenIssuer(
                        unexpectedProperties,
                        encoder,
                        Clock.fixed(now, ZoneOffset.UTC)
                );

        String token =
                unexpectedIssuer.createAccessToken(user());

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    private static User user() {
        return new User(
                USER_ID,
                "customer@example.com",
                "Customer",
                "encoded-password",
                true,
                Set.of(UserRole.USER, UserRole.ADMIN),
                null,
                null
        );
    }
}