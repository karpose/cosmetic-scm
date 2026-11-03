package com.cosmetic.scm.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlHours;

    public JwtService(JwtEncoder encoder, @Value("${scm.jwt.ttl-hours:12}") long ttlHours) {
        this.encoder = encoder;
        this.ttlHours = ttlHours;
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("cosmetic-scm")
                .issuedAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("roles", user.getRoles().stream().map(Enum::name).toList())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
