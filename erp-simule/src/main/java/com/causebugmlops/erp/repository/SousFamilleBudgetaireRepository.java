package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.budget.SousFamilleBudgetaire;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SousFamilleBudgetaireRepository extends JpaRepository<SousFamilleBudgetaire, Long> {

    /**
     * A utiliser exclusivement dans la section critique de verification/engagement
     * budgetaire (CU-01 etape 5, CU-02 branche A). C'est le point precis a
     * neutraliser pour le scenario de fault injection "race condition budgetaire"
     * (bug candidat n1, cf. note d'architecture Phase 2) : deux demandes concurrentes
     * sur la meme sous-famille lisent toutes deux "budget suffisant" avant que l'une
     * ne debite, provoquant un depassement silencieux si ce verrou est desactive.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from SousFamilleBudgetaire s where s.id = :id")
    Optional<SousFamilleBudgetaire> findByIdForUpdate(@Param("id") Long id);
}
