package com.sdv.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-BE-007 검증: realm_access.roles / resource_access."sdv-backend".roles만
 * 인식된 Role(USER/ADMIN)로 변환하고, 그 외 위치/값은 절대 권한으로 인정하지
 * 않는다(AUT-001, AUT-002).
 */
class JwtAuthenticationConverterTest {

    private final JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

    @Test
    void mapsRecognizedRealmRolesAndIgnoresUnknownOnes() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("USER", "ADMIN", "unknown-role"))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(authorityNames(token)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void mapsRecognizedClientRolesFromResourceAccess() {
        Jwt jwt = jwtWithClaims(Map.of(
                "resource_access", Map.of("sdv-backend", Map.of("roles", List.of("ADMIN")))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(authorityNames(token)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void ignoresUnrecognizedRoleClaimValues() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("SUPERUSER", "owner"))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void ignoresArbitraryTopLevelRoleClaimNotInSupportedLocation() {
        // 최상위 "roles":["ADMIN"] - realm_access/resource_access가 아니므로 무시되어야 한다.
        Jwt jwt = jwtWithClaims(Map.of("roles", List.of("ADMIN")));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void deduplicatesRoleAppearingInBothRealmAndClientClaims() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN")),
                "resource_access", Map.of("sdv-backend", Map.of("roles", List.of("ADMIN")))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).hasSize(1);
    }

    @Test
    void ignoresCaseVariantsAndWhitespacePaddedRoleNamesInRealmClaim() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles",
                        List.of("admin", "Admin", "user", "ROLE_ADMIN", " ADMIN", "ADMIN "))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .as("only the exact strings USER/ADMIN may be recognized - no normalization")
                .isEmpty();
    }

    @Test
    void ignoresCaseVariantsAndWhitespacePaddedRoleNamesInClientClaim() {
        Jwt jwt = jwtWithClaims(Map.of(
                "resource_access", Map.of("sdv-backend", Map.of("roles",
                        List.of("admin", "Admin", " USER", "user ")))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void ignoresRolesBelongingOnlyToAnotherClient() {
        Jwt jwt = jwtWithClaims(Map.of(
                "resource_access", Map.of("some-other-client", Map.of("roles", List.of("ADMIN")))));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void tokenIsUsableWithNoRoleClaimsAtAll() {
        Jwt jwt = jwtWithClaims(Map.of());

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities()).isEmpty();
        assertThat(token.getPrincipal()).isSameAs(jwt);
    }

    private Set<String> authorityNames(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private Jwt jwtWithClaims(Map<String, Object> extraClaims) {
        Map<String, Object> claims = new LinkedHashMap<>(extraClaims);
        claims.putIfAbsent("sub", "test-subject");
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
    }
}
