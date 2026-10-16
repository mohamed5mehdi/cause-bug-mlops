package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.article.Article;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long> {

    /**
     * A utiliser exclusivement lors de la verification/decrementation de stock
     * (CU-03, chemin "En Stock"). Bug candidat n2 pour le module de fault injection
     * : sans ce verrou, deux demandes concurrentes peuvent toutes deux constater
     * "disponible" avant decrementation, provoquant une survente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select a from Article a where a.id = :id")
    Optional<Article> findByIdForUpdate(@Param("id") Long id);
}
