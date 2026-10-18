package com.causebugmlops.erp.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Decision d'un valideur (N+1, AMG, DAF) : valider ou rejeter, avec un commentaire.
 * En cas de rejet, le commentaire devient le motif de rejet enregistre sur la demande.
 *
 * @param valider     true = valider, false = rejeter (obligatoire : pas de decision implicite)
 * @param commentaire facultatif, 1000 caracteres max (taille de la colonne motif_rejet)
 */
public record DecisionRequest(
        @NotNull Boolean valider,
        @Size(max = 1000) String commentaire
) {}
