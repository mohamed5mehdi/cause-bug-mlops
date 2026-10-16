package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.budget.BudgetPieces;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BudgetPiecesRepository extends JpaRepository<BudgetPieces, Long> {

    Optional<BudgetPieces> findByExercice(String exercice);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select b from BudgetPieces b where b.id = :id")
    Optional<BudgetPieces> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select b from BudgetPieces b where b.exercice = :exercice")
    Optional<BudgetPieces> findByExerciceForUpdate(@Param("exercice") String exercice);
}
