package com.makaohub.backend.auth.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserRoleId implements Serializable {

    private UUID user;
    private Short role;

    public UserRoleId() {
    }

    public UserRoleId(UUID user, Short role) {
        this.user = user;
        this.role = role;
    }

    public UUID getUser() {
        return user;
    }

    public Short getRole() {
        return role;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof UserRoleId that)) {
            return false;
        }

        return Objects.equals(user, that.user)
                && Objects.equals(role, that.role);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user, role);
    }
}