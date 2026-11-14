package com.sugarcrumbs.identity.application;

import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.persistence.OwnerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

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

    private static Owner ownerWithId(UUID id) {
        Owner owner = Owner.builder()
                .email("baker@example.com").passwordHash("hash").ownerName("Owner").businessName("SugarCrumbs")
                .build();
        ReflectionTestUtils.setField(owner, "id", id);
        return owner;
    }
}
