package com.makaohub.backend.auth.repository;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.domain.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository
        extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findAllByUser_Id(UUID userId);

    boolean existsByUser_IdAndRole_Name(
            UUID userId,
            RoleName roleName
    );
}