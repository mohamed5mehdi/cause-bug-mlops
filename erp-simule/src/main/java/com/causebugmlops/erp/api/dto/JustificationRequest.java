package com.causebugmlops.erp.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirmation de l'Acheteur apres validation d'un ajustement : une justification
 * obligatoire (colonne justification_acheteur, NOT NULL, 1000 caracteres max).
 */
public record JustificationRequest(
        @NotBlank @Size(max = 1000) String justification
) {}
