package com.makaohub.backend.auth.repository;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.domain.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository
        extends JpaRepository<UserRole, UserRoleId> {

    @Query("""
            SELECT COUNT(assignment) > 0 FROM UserRole assignment
            JOIN assignment.user account
            WHERE account.id = :userId AND assignment.role.name = :role
              AND account.accountStatus = com.makaohub.backend.auth.domain.AccountStatus.ACTIVE
              AND account.roleSelectionRequired = false
              AND account.authorizationVersion = :version
            """)
    boolean isCurrentAuthorization(@Param("userId") UUID userId,
                                   @Param("role") RoleName role,
                                   @Param("version") long version);

    List<UserRole> findAllByUser_Id(UUID userId);

    boolean existsByUser_IdAndRole_Name(
            UUID userId,
            RoleName roleName
    );
}
