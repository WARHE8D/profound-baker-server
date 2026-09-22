package com.sugarcrumbs.server.entity;


import com.sugarcrumbs.server.entity.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Menu category, e.g. "Cakes", "Cupcakes", "Custom Orders".
 * Kept intentionally minimal — expand only if the domain needs it
 * (e.g. a display order or a parent category for subcategories).
 */
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @NotBlank
    @Size(max = 100)
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    protected Category() {
        // required by JPA
    }

    public Category(String name) {
        rename(name);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("category name cannot be blank");
        }
        this.name = newName;
    }

    public String getName() {
        return name;
    }
}