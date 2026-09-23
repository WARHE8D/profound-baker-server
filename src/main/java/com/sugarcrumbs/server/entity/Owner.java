package com.sugarcrumbs.server.entity;

import com.sugarcrumbs.server.entity.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The patissier who owns and runs the business.
 *
 * <p>This system is single-tenant by design: exactly one {@code Owner}
 * row is expected to exist. That invariant is deliberately <b>not</b>
 * enforced here at the entity level (a unique fake "tenant key" column
 * would be a hack); enforce it in the service layer — e.g. a
 * {@code OwnerService.getTheOwner()} that fails loudly if zero or more
 * than one row exists, and an application-startup check. This keeps the
 * entity honest about what JPA can actually guarantee.
 *
 * <p><b>Security note:</b> this entity stores only a {@code passwordHash}
 * (e.g. BCrypt), never a plaintext password. Building a Spring Security
 * {@code UserDetails} from this entity is a service-layer concern, not
 * something this entity should know about — keeps the domain model free
 * of framework coupling.
 */
@Entity
@Table(
        name = "owners",
        uniqueConstraints = @UniqueConstraint(name = "uk_owners_email", columnNames = "email")
)
public class Owner extends BaseEntity {

    @NotBlank
    @Size(max = 150)
    @Column(name = "business_name", nullable = false, length = 150)
    private String businessName;

    @NotBlank
    @Size(max = 150)
    @Column(name = "owner_name", nullable = false, length = 150)
    private String ownerName;

    @NotBlank
    @Email
    @Size(max = 254)
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /** BCrypt (or equivalent) hash — never store or accept a raw password here. */
    @NotBlank
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Size(max = 30)
    @Column(name = "phone", length = 30)
    private String phone;

    @Size(max = 300)
    @Column(name = "logo_url", length = 300)
    private String logoUrl;

    @Embedded
    private Address address;

    protected Owner() {
        // required by JPA
    }

    private Owner(Builder builder) {
        this.businessName = requireNonBlank(builder.businessName, "businessName");
        this.ownerName = requireNonBlank(builder.ownerName, "ownerName");
        this.email = requireNonBlank(builder.email, "email").toLowerCase();
        this.passwordHash = requireNonBlank(builder.passwordHash, "passwordHash");
        this.phone = builder.phone;
        this.logoUrl = builder.logoUrl;
        this.address = builder.address;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior ----

    public void updateProfile(String businessName, String ownerName, String phone, String logoUrl, Address address) {
        this.businessName = requireNonBlank(businessName, "businessName");
        this.ownerName = requireNonBlank(ownerName, "ownerName");
        this.phone = phone;
        this.logoUrl = logoUrl;
        this.address = address;
    }

    /** Changes the login email. Uniqueness is enforced by the DB constraint + a pre-check in the service layer. */
    public void changeEmail(String newEmail) {
        this.email = requireNonBlank(newEmail, "email").toLowerCase();
    }

    /** Accepts an already-hashed password. Hashing happens in the service layer (e.g. via {@code PasswordEncoder}). */
    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = requireNonBlank(newPasswordHash, "passwordHash");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }

    // ---- Getters only ----

    public String getBusinessName() {
        return businessName;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPhone() {
        return phone;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public Address getAddress() {
        return address;
    }

    @Override
    public String toString() {
        // passwordHash intentionally excluded from toString/logs
        return "Owner{id=%s, businessName='%s', email='%s'}".formatted(getId(), businessName, email);
    }

    public static final class Builder {
        private String businessName;
        private String ownerName;
        private String email;
        private String passwordHash;
        private String phone;
        private String logoUrl;
        private Address address;

        private Builder() {
        }

        public Builder businessName(String businessName) {
            this.businessName = businessName;
            return this;
        }

        public Builder ownerName(String ownerName) {
            this.ownerName = ownerName;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder passwordHash(String passwordHash) {
            this.passwordHash = passwordHash;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder logoUrl(String logoUrl) {
            this.logoUrl = logoUrl;
            return this;
        }

        public Builder address(Address address) {
            this.address = address;
            return this;
        }

        public Owner build() {
            return new Owner(this);
        }
    }
}
