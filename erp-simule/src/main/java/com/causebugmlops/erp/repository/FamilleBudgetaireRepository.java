package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.budget.FamilleBudgetaire;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FamilleBudgetaireRepository extends JpaRepository<FamilleBudgetaire, Long> {

    /**
     * A utiliser exclusivement dans la section critique d'engagement budgetaire
     * (ajustement de type FAMILLE, CU-03 branche B). Verrou {@code PESSIMISTIC_WRITE}
     * avec timeout explicite de 3s : un timeout absent exposerait le circuit DG a un
     * blocage indefini en cas de contention, ce qui degraderait directement le SLA
     * de traitement mesure par le tableau de bord.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select f from FamilleBudgetaire f where f.id = :id")
    Optional<FamilleBudgetaire> findByIdForUpdate(@Param("id") Long id);
}
