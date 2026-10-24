/** Mode demonstration statique (GitHub Pages).
 *
 * Active au build par VITE_DEMO=true. Les appels a l'API ne partent pas vers aiops-engine :
 * ils sont servis par des instantanes JSON reels (public/demo), exportes depuis une plateforme
 * lancee en local par scripts/exporter_demo.py. Aucun backend expose, aucun appel au modele. */
export const MODE_DEMO = import.meta.env.VITE_DEMO === 'true';

const RACINE = `${import.meta.env.BASE_URL}demo/`;
const UUID = /^\/api\/(traces|diagnostics)\/([0-9a-f-]{36})$/;

/** Fichier d'instantane correspondant a une requete GET, ou null si l'action n'existe pas en demo. */
function fichierPour(methode: string, chemin: string): string | null {
  if (methode !== 'GET') return null;
  if (chemin === '/api/dossiers') return 'dossiers.json';
  const trouve = UUID.exec(chemin);
  if (trouve === null) return null;
  const [, type, identifiant] = trouve;
  if (type === undefined || identifiant === undefined) return null;
  return `${type}/${identifiant}.json`;
}

/** Remplace fetch dans le client openapi-fetch quand MODE_DEMO est actif. */
export async function fetchDemo(requete: Request): Promise<Response> {
  const fichier = fichierPour(requete.method, new URL(requete.url).pathname);
  if (fichier === null) {
    return new Response(JSON.stringify({ detail: 'Action indisponible dans la demo statique.' }), {
      status: 501,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  return fetch(`${RACINE}${fichier}`);
}
