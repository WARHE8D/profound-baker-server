package com.sugarcrumbs.identity.application;

import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.persistence.OwnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OwnerServiceTests {

    private final OwnerRepository repository = mock(OwnerRepository.class);
    private final OwnerService service = new OwnerService(repository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anOwnerRequestResolvesTheOwnerFromTheTokenWithoutTouchingTheDatabase() {
        UUID ownerId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(ownerId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(service.currentOwnerId()).isEqualTo(ownerId);
        verifyNoInteractions(repository);
    }

    @Test
    void aPublicRequestFallsBackToTheSingleOwner() {
        Owner owner = ownerWithId(UUID.randomUUID());
        when(repository.findAll()).thenReturn(List.of(owner));
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(service.currentOwnerId()).isEqualTo(owner.getId());
    }

    @Test
    void outsideAnyRequestFallsBackToTheSingleOwner() {
        Owner owner = ownerWithId(UUID.randomUUID());
        when(repository.findAll()).thenReturn(List.of(owner));

        assertThat(service.currentOwnerId()).isEqualTo(owner.getId());
    }

    @Test
    void theSingleOwnerLookupRefusesZeroOrSeveralOwners() {
        when(repository.findAll()).thenReturn(List.of());
        assertThatThrownBy(service::getTheOwner).isInstanceOf(IllegalStateException.class);

        when(repository.findAll()).thenReturn(List.of(ownerWithId(UUID.randomUUID()), ownerWithId(UUID.randomUUID())));
        assertThatThrownBy(service::getTheOwner).hasMessageContaining("single-tenant");
    }

    @Test
    void settingsCarryTheOwnersTimeZone() {
        when(repository.findAll()).thenReturn(List.of(ownerWithId(UUID.randomUUID())));

        assertThat(service.settings().timeZone()).isEqualTo(ZoneId.of("Europe/London"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/London", "America/New_York", "Asia/Kolkata", "UTC"})
    void acceptsRegionTimeZones(String id) {
        assertThat(OwnerService.parseZone(id)).isEqualTo(ZoneId.of(id));
    }

    /** A fixed offset ignores daylight saving, which would shift every slot for half the year. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"+01:00", "GMT+1", "UTC+05:30", "Mars/Olympus_Mons", "europe/london"})
    void refusesFixedOffsetsAndUnknownZones(String id) {
        assertThatThrownBy(() -> OwnerService.parseZone(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IANA");
    }

    private static Owner ownerWithId(UUID id) {
        Owner owner = Owner.builder()
                .email("baker@example.com").passwordHash("hash").ownerName("Owner").businessName("SugarCrumbs")
                .timeZone(ZoneId.of("Europe/London"))
                .build();
        ReflectionTestUtils.setField(owner, "id", id);
        return owner;
    }
}
