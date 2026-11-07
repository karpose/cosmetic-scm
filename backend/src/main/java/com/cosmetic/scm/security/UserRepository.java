package com.cosmetic.scm.security;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 开了门户账号的单位。 */
    @org.springframework.data.jpa.repository.Query("select distinct u.partnerId from AppUser u where u.partnerId is not null")
    java.util.Set<Long> partnersWithAccounts();

    /** 某个门户单位下的账号。 */
    @org.springframework.data.jpa.repository.Query("select u.username from AppUser u where u.partnerId = :partnerId")
    java.util.List<String> usernamesOfPartner(@org.springframework.data.repository.query.Param("partnerId") Long partnerId);
}
