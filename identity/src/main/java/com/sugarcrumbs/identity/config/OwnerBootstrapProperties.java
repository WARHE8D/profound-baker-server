package com.sugarcrumbs.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Owner created on first start ({@code app.owner.*}). Read only while no Owner
 * exists; after that the env vars can (and should) be removed.
 *
 * @param email           login email ({@code APP_OWNER_EMAIL})
 * @param initialPassword first password ({@code APP_OWNER_INITIAL_PASSWORD}); change it after the first login
 * @param name            the Owner's name, editable later through the profile API (#12)
 * @param businessName    the bakery's name, editable later through the profile API (#12)
 */
@ConfigurationProperties("app.owner")
public record OwnerBootstrapProperties(
        String email,
        String initialPassword,
        @DefaultValue("Owner") String name,
        @DefaultValue("SugarCrumbs") String businessName
) {

    /** Binding leaves an unresolvable {@code ${VAR}} in place, so that counts as unset too. */
    public static boolean isSet(String value) {
        return value != null && !value.isBlank() && !value.startsWith("${");
    }

    @Override
    public String toString() {
        return "OwnerBootstrapProperties[initialPassword=redacted]";
    }
}
