package com.causebugmlops.erp.domain.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Superclasse pour les entites mutables du domaine (budget, article, demande, PO,
 * fournisseur). createdAt/updatedAt sont geres automatiquement par Spring Data JPA
 * Auditing (voir {@code @EnableJpaAuditing} sur la classe principale) - jamais
 * positionnes manuellement par le code applicatif.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
