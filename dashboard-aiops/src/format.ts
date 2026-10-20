import type { Composant } from './api/client';

const DATE_HEURE = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'medium' });
const HEURE = new Intl.DateTimeFormat('fr-FR', { timeStyle: 'medium' });

export function dateHeure(iso: string): string {
  return DATE_HEURE.format(new Date(iso));
}

export function heure(iso: string): string {
  return HEURE.format(new Date(iso));
}

/** Les 8 premiers caracteres d'un UUID suffisent a distinguer les dossiers a l'ecran. */
export function idCourt(uuid: string): string {
  return uuid.slice(0, 8);
}

export function pourcentage(valeur: number): string {
  return `${String(Math.round(valeur * 100))} %`;
}

export const LIBELLE_COMPOSANT: Record<Composant, string> = {
  CODE_JAVA: 'Code Java',
  BASE_DE_DONNEES: 'Base de donnees',
  FLUX_METIER: 'Flux metier',
};
