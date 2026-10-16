package com.causebugmlops.erp.repository;

import com.causebugmlops.erp.domain.utilisateur.Utilisateur;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UtilisateurRepository extends JpaRepository<Utilisateur, Long> {
}
