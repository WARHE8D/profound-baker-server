package com.sugarcrumbs.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * Contact details for a guest booking — no {@code Customer} entity or
 * login required. Modeled as an embeddable value object (not its own
 * table) because it has no identity or lifecycle independent of the
 * {@link Booking} it belongs to; two bookings with the same email are
 * two separate contacts, not a shared one.
 */
@Embeddable
public class GuestContact {

    @NotBlank
    @Size(max = 150)
    @Column(name = "guest_name", nullable = false, length = 150)
    private String name;

    @NotBlank
    @Email
    @Size(max = 254)
    @Column(name = "guest_email", nullable = false, length = 254)
    private String email;

    @NotBlank
    @Size(max = 30)
    @Column(name = "guest_phone", nullable = false, length = 30)
    private String phone;

    protected GuestContact() {
        // required by JPA
    }

    public GuestContact(String name, String email, String phone) {
        this.name = requireNonBlank(name, "name");
        this.email = requireNonBlank(email, "email").toLowerCase();
        this.phone = requireNonBlank(phone, "phone");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GuestContact other)) return false;
        return Objects.equals(name, other.name)
                && Objects.equals(email, other.email)
                && Objects.equals(phone, other.phone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, email, phone);
    }
}
