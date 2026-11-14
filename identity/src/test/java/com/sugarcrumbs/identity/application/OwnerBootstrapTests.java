package com.sugarcrumbs.identity.application;

import com.sugarcrumbs.identity.config.OwnerBootstrapProperties;
import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.persistence.OwnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OwnerBootstrapTests {

    private static final String PASSWORD = "a-long-enough-password";

    // Cost 4 keeps the test fast; production uses 12 (TokenConfig).
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final OwnerRepository repository = mock(OwnerRepository.class);

    @Test
    void createsTheOwnerFromTheEnvironmentWhenNoneExists(CapturedOutput output) {
        when(repository.count()).thenReturn(0L);

        bootstrap(" Baker@Example.COM ", PASSWORD).ensureOwner();

        ArgumentCaptor<Owner> saved = ArgumentCaptor.forClass(Owner.class);
        verify(repository).save(saved.capture());
        Owner owner = saved.getValue();
        assertThat(owner.getEmail()).isEqualTo("baker@example.com");
        assertThat(owner.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, owner.getPasswordHash())).isTrue();
        assertThat(owner.getOwnerName()).isEqualTo("Owner");
        assertThat(owner.getBusinessName()).isEqualTo("SugarCrumbs");
        assertThat(output.getOut()).contains("change the password").doesNotContain("baker@example.com", PASSWORD);
    }

    @Test
    void leavesAnExistingOwnerAlone() {
        when(repository.count()).thenReturn(1L);

        bootstrap("new@example.com", PASSWORD).ensureOwner();

        verify(repository, never()).save(any());
    }

    @Test
    void anExistingOwnerNeedsNoEnvironment() {
        when(repository.count()).thenReturn(1L);

        bootstrap(null, "${APP_OWNER_INITIAL_PASSWORD}").ensureOwner();

        verify(repository, never()).save(any());
    }

    @Test
    void warnsWhenTheInitialPasswordOutlivesTheBootstrap(CapturedOutput output) {
        when(repository.count()).thenReturn(1L);

        bootstrap("baker@example.com", PASSWORD).ensureOwner();

        assertThat(output.getOut()).contains("WARN").contains("APP_OWNER_INITIAL_PASSWORD is still set");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "${APP_OWNER_EMAIL}"})
    void failsFastWithoutAnOwnerOrAnEmail(String email) {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap(email, PASSWORD).ensureOwner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_OWNER_EMAIL")
                .hasMessageContaining("APP_OWNER_INITIAL_PASSWORD");
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"${APP_OWNER_INITIAL_PASSWORD}"})
    void failsFastWithoutAnOwnerOrAPassword(String password) {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap("baker@example.com", password).ensureOwner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_OWNER_INITIAL_PASSWORD");
    }

    @Test
    void rejectsAShortInitialPasswordWithoutEchoingIt() {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap("baker@example.com", "short-pw").ensureOwner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12 characters")
                .hasMessageNotContaining("short-pw");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAnInvalidEmail() {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap("not-an-email", PASSWORD).ensureOwner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_OWNER_EMAIL");
    }

    @Test
    void refusesToStartWithMoreThanOneOwner() {
        when(repository.count()).thenReturn(2L);

        assertThatThrownBy(() -> bootstrap("baker@example.com", PASSWORD).ensureOwner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-tenant");
    }

    private OwnerBootstrap bootstrap(String email, String password) {
        OwnerBootstrapProperties properties = new OwnerBootstrapProperties(email, password, "Owner", "SugarCrumbs");
        return new OwnerBootstrap(repository, passwordEncoder, properties, null);
    }
}
