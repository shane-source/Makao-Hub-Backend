package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.RoleRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegacyRoleSelectionService {
    private final EntityManager entityManager;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;

    public LegacyRoleSelectionService(EntityManager entityManager, RoleRepository roles,
                                      UserRoleRepository userRoles) {
        this.entityManager = entityManager;
        this.roles = roles;
        this.userRoles = userRoles;
    }

    // Called only AFTER password or Google credentials have been verified.
    // MANDATORY keeps the role choice, audit and token issuance in the login transaction.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void resolve(User user, RoleName selectedRole) {
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AuthException(AuthException.Reason.ACCOUNT_UNAVAILABLE,
                    "This account is unavailable.");
        }
        if (!user.isRoleSelectionRequired()) {
            if (selectedRole != null) {
                throw new AuthException(AuthException.Reason.ROLE_SELECTION_NOT_REQUIRED,
                        "This account already has a role. Use the profile role-switch flow.");
            }
            return;
        }
        if (selectedRole == null) {
            throw new AuthException(AuthException.Reason.ROLE_SELECTION_REQUIRED,
                    "Choose RENTER or SUBLESSOR to continue with your existing account.");
        }
        if (selectedRole != RoleName.RENTER && selectedRole != RoleName.SUBLESSOR) {
            throw new AuthException(AuthException.Reason.INVALID_ROLE_SELECTION,
                    "Choose RENTER or SUBLESSOR.");
        }
        if (!userRoles.findAllByUser_Id(user.getId()).isEmpty()) {
            throw new IllegalStateException("A pending role choice must have no active role.");
        }
        var role = roles.findByName(selectedRole)
                .orElseThrow(() -> new IllegalStateException("The selected role is not configured."));
        // Flush the assignment first: database triggers initialize its own verification record.
        userRoles.saveAndFlush(new UserRole(user, role, null));
        user.completeRoleSelection();
        // The database validates and audits the choice; any failure rolls back the login.
        entityManager.flush();
    }
}
