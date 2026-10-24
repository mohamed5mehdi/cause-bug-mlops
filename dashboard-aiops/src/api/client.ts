import createClient from 'openapi-fetch';

import { fetchDemo, MODE_DEMO } from './demo';
import type { components, paths } from './schema';

/** Client HTTP type a partir du contrat OpenAPI d'aiops-engine (ADR-014).
 * Meme origine : les appels partent vers /api, que nginx (ou Vite en dev) relaie.
 * En demo statique, ils sont servis par les instantanes JSON (voir demo.ts). */
export const api = createClient<paths>(
  MODE_DEMO ? { baseUrl: '/api', fetch: fetchDemo } : { baseUrl: '/api' },
);

type Schemas = components['schemas'];
export type EtatSante = Schemas['EtatSante'];
export type DossierResume = Schemas['DossierResume'];
export type TraceEvenement = Schemas['TraceEvenement'];
export type DiagnosticReponse = Schemas['DiagnosticReponse'];
export type DiagnosticHistorique = Schemas['DiagnosticHistorique'];
export type Composant = DiagnosticReponse['composant'];

/** Messages lisibles pour les codes d'erreur documentes de POST /diagnose (ADR-013, ADR-015). */
export function messageErreurDiagnostic(statut: number): string {
  if (MODE_DEMO) {
    return "Demo statique : les diagnostics affiches ont ete produits en local. Pour en lancer un nouveau, demarrer le projet (voir le README).";
  }
  switch (statut) {
    case 404:
      return 'Aucune trace pour ce dossier : rien a diagnostiquer.';
    case 502:
      return "Le modele n'a pas fourni de reponse exploitable (Gemini sature ?). Reessayer.";
    case 503:
      return 'Diagnostic inactif : cle Gemini absente cote aiops-engine.';
    case 504:
      return 'Diagnostic trop long (plus de 130 s). Reessayer dans un instant.';
    default:
      return `Erreur inattendue (HTTP ${String(statut)}).`;
  }
}
