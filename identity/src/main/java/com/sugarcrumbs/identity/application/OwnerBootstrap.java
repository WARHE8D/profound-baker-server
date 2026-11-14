package com.sugarcrumbs.identity.application;

import com.sugarcrumbs.identity.config.OwnerBootstrapProperties;
import com.sugarcrumbs.identity.domain.Owner;
import com.sugarcrumbs.identity.persistence.OwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.regex.Pattern;

import static com.sugarcrumbs.identity.config.OwnerBootstrapProperties.isSet;

/**
 * Creates the single Owner on first start (#11, CLAUDE.md §5). There is no signup
 * endpoint, ever: the Owner comes from {@code APP_OWNER_EMAIL} and
 * {@code APP_OWNER_INITIAL_PASSWORD}. With no Owner and no such variables the app
 * refuses to start, since every owner route and availability lookup would fail.
 *
 * <p>Runs once all beans exist (Flyway has migrated) and before the web server
 * starts, so no request ever sees the app without an Owner.
 */
@Component
class OwnerBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    static final int MIN_PASSWORD_LENGTH = 12;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final OwnerRepository ownerRepository;
    private final PasswordEncoder passwordEncoder;
    private final OwnerBootstrapProperties properties;
    private final TransactionTemplate transaction;

    OwnerBootstrap(OwnerRepository ownerRepository, PasswordEncoder passwordEncoder,
                   OwnerBootstrapProperties properties, TransactionTemplate transactionTemplate) {
        this.ownerRepository = ownerRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.transaction = transactionTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        transaction.executeWithoutResult(status -> ensureOwner());
    }

    void ensureOwner() {
        long owners = ownerRepository.count();
        if (owners > 1) {
            throw new IllegalStateException(
                    "found " + owners + " Owners but this system is single-tenant; remove the extra rows");
        }
        if (owners == 1) {
            if (isSet(properties.initialPassword())) {
                log.warn("APP_OWNER_INITIAL_PASSWORD is still set although the Owner exists; remove it");
            }
            return;
        }
        if (!isSet(properties.email()) || !isSet(properties.initialPassword())) {
            throw new IllegalStateException("no Owner exists: set APP_OWNER_EMAIL and APP_OWNER_INITIAL_PASSWORD "
                    + "to create one on startup");
        }
        String email = properties.email().strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches() || email.length() > 254) {
            throw new IllegalStateException("APP_OWNER_EMAIL is not a valid email address");
        }
        if (properties.initialPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "APP_OWNER_INITIAL_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        ownerRepository.save(Owner.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(properties.initialPassword()))
                .ownerName(properties.name())
                .businessName(properties.businessName())
                .build());
        // No email here: logs must not carry PII at INFO/WARN (CLAUDE.md §6).
        log.warn("Created the Owner from APP_OWNER_EMAIL; log in, change the password, "
                + "then remove APP_OWNER_INITIAL_PASSWORD from the environment");
    }
}
