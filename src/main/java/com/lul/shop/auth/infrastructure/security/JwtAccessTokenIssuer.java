package com.lul.shop.auth.infrastructure.security;

import com.lul.shop.auth.application.port.AccessTokenIssuer;
import com.lul.shop.auth.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtProperties properties;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;

    public JwtAccessTokenIssuer(JwtProperties properties,
                                JwtEncoder jwtEncoder,
                                Clock clock) {
        this.properties = properties;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
    }

    @Override
    public String createAccessToken(User user) {
        Instant now = clock.instant();

        List<String> roles = user.getRoles()
                .stream()
                .map(Enum::name)
                .sorted()
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
