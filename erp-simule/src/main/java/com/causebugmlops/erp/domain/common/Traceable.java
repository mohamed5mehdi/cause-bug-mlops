package com.causebugmlops.erp.domain.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Superclasse pour les entites append-only : documents de tracabilite legale et
 * piste d'audit fonctionnelle (HistoriqueValidation, AjustementBudgetaire, GRN, GRC).
 * Un seul horodatage de creation, jamais de champ updatedAt : ces entites ne sont,
 * par construction, jamais modifiees apres persistance. Toute tentative de mise a
 * jour constaterait une anomalie metier plutot qu'une operation legitime.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Traceable {

    @CreatedDate
    @Column(name = "horodatage", nullable = false, updatable = false)
    private Instant horodatage;
}
