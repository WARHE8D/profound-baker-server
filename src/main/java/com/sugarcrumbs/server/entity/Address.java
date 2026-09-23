package com.sugarcrumbs.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;

import java.util.Objects;

/**
 * Postal address as a value object (no identity of its own, no
 * equals/hashCode by id — embedded directly into {@link Owner}'s table
 * rather than given its own table and foreign key it doesn't need).
 */
@Embeddable
public class Address {

    @Size(max = 200)
    @Column(name = "street")
    private String street;

    @Size(max = 100)
    @Column(name = "city")
    private String city;

    @Size(max = 100)
    @Column(name = "region")
    private String region;

    @Size(max = 20)
    @Column(name = "postal_code")
    private String postalCode;

    @Size(max = 100)
    @Column(name = "country")
    private String country;

    protected Address() {
        // required by JPA
    }

    public Address(String street, String city, String region, String postalCode, String country) {
        this.street = street;
        this.city = city;
        this.region = region;
        this.postalCode = postalCode;
        this.country = country;
    }

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getRegion() {
        return region;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Address other)) return false;
        return Objects.equals(street, other.street)
                && Objects.equals(city, other.city)
                && Objects.equals(region, other.region)
                && Objects.equals(postalCode, other.postalCode)
                && Objects.equals(country, other.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(street, city, region, postalCode, country);
    }
}
