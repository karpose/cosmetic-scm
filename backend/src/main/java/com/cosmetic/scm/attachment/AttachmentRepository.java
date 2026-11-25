package com.cosmetic.scm.attachment;

import java.util.List;

import com.cosmetic.scm.domain.OwnerType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType ownerType, Long ownerId);

    boolean existsByOwnerTypeAndOwnerId(OwnerType ownerType, Long ownerId);

    /** 一批单据里有附件的那些（列表一次查完，不逐条查）。 */
    @org.springframework.data.jpa.repository.Query("select distinct a.ownerId from Attachment a where a.ownerType = :ownerType and a.ownerId in :ownerIds")
    List<Long> findOwnerIdsWithFiles(@org.springframework.data.repository.query.Param("ownerType") OwnerType ownerType,
                                     @org.springframework.data.repository.query.Param("ownerIds") java.util.Collection<Long> ownerIds);
}
