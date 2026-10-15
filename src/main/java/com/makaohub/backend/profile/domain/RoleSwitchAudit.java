package com.makaohub.backend.profile.domain;

import com.makaohub.backend.auth.domain.RoleName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "role_switch_audit")
public class RoleSwitchAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "user_id",
            nullable = false,
            updatable = false
    )
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "previous_role",
            nullable = false,
            updatable = false,
            length = 40
    )
    private RoleName previousRole;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "selected_role",
            nullable = false,
            updatable = false,
            length = 40
    )
    private RoleName selectedRole;

    @Column(
            name = "authentication_method",
            nullable = false,
            updatable = false,
            length = 20
    )
    private String authenticationMethod;

    @Column(
            name = "authorization_version",
            nullable = false,
            updatable = false
    )
    private long authorizationVersion;

    @Column(
            name = "occurred_at",
            nullable = false,
            updatable = false
    )
    private Instant occurredAt;

    protected RoleSwitchAudit() {
    }

    public RoleSwitchAudit(
            UUID userId,
            RoleName previousRole,
            RoleName selectedRole,
            String authenticationMethod,
            long authorizationVersion,
            Instant occurredAt
    ) {
        this.userId = userId;
        this.previousRole = previousRole;
        this.selectedRole = selectedRole;
        this.authenticationMethod = authenticationMethod;
        this.authorizationVersion = authorizationVersion;
        this.occurredAt = occurredAt;
    }
}