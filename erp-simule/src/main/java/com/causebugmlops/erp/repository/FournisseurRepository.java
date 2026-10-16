package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.fournisseur.Fournisseur;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FournisseurRepository extends JpaRepository<Fournisseur, Long> {
}
