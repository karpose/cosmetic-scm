package com.cosmetic.scm.security;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import com.cosmetic.scm.domain.Role;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    private String passwordHash;

    private String displayName;

    /** 接收每日待办的邮箱，选填。 */
    private String email;

    private boolean enabled = true;

    private LocalDateTime createdAt;

    /** 门户账号所属单位（受托厂或经销商）；内部账号为空 */
    private Long partnerId;

    private int failedLogins;

    private LocalDateTime lockedUntil;

    private LocalDateTime passwordChangedAt;

    private boolean mustChangePassword;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_name")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = new HashSet<>();

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean isPortal() {
        return roles.stream().anyMatch(Role::isPortal);
    }
}
