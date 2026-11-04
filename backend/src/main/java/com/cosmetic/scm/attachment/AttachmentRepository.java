package com.cosmetic.scm.attachment;

import java.util.List;

import com.cosmetic.scm.domain.OwnerType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType ownerType, Long ownerId);
}
