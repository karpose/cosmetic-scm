package com.cosmetic.scm.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cosmetic.scm.domain.Role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 开了门户账号的单位。 */
    @Query("select distinct u.partnerId from AppUser u where u.partnerId is not null")
    Set<Long> partnersWithAccounts();

    @Query("select distinct u.partnerId from AppUser u join u.roles r where u.enabled = true and r = :role and u.partnerId in :partnerIds")
    Set<Long> partnersWithEnabledPortalRole(@Param("role") Role role, @Param("partnerIds") Collection<Long> partnerIds);

    /** 某个门户单位下的账号。 */
    @Query("select u.username from AppUser u where u.partnerId = :partnerId")
    List<String> usernamesOfPartner(@Param("partnerId") Long partnerId);
}
