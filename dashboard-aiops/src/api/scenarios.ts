import { MODE_DEMO } from './demo';

/** Panne injectee dans un dossier de la demo (protocole d'evaluation, docs/evaluation). */
export type Cas = 'P1' | 'P2' | 'P3' | 'T';

export const LIBELLES: Readonly<Record<Cas, string>> = {
  P1: 'P1 · Verrou',
  P2: 'P2 · TVA',
  P3: 'P3 · Etape contournee',
  T: 'Temoin sain',
};

/** correlation_id -> panne injectee. Vide hors demo : le systeme observe n'expose pas cette information. */
export type Scenarios = Readonly<Record<string, Cas>>;

function estCas(valeur: unknown): valeur is Cas {
  return valeur === 'P1' || valeur === 'P2' || valeur === 'P3' || valeur === 'T';
}

export async function chargerScenarios(): Promise<Scenarios> {
  if (!MODE_DEMO) return {};
  try {
    const reponse = await fetch(`${import.meta.env.BASE_URL}demo/scenarios.json`);
    if (!reponse.ok) return {};
    const brut: unknown = await reponse.json();
    if (typeof brut !== 'object' || brut === null) return {};
    const resultat: Record<string, Cas> = {};
    for (const [id, cas] of Object.entries(brut as Record<string, unknown>)) {
      if (estCas(cas)) resultat[id] = cas;
    }
    return resultat;
  } catch {
    return {};
  }
}
