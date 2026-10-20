import { useEffect, useState } from 'react';

import { api, type TraceEvenement } from '../api/client';
import { heure } from '../format';

type Chargement =
  | { etape: 'en-cours' }
  | { etape: 'erreur'; message: string }
  | { etape: 'pret'; traces: TraceEvenement[] };

/** Chronologie technique d'un dossier (GET /traces/{id}), numerotee T1..Tn
 * comme dans le dossier d'enquete envoye a Gemini : les preuves citees s'y retrouvent. */
export default function Chronologie({ correlationId }: { correlationId: string }) {
  const [etat, setEtat] = useState<Chargement>({ etape: 'en-cours' });

  useEffect(() => {
    // Si le dossier change avant la reponse, la reponse obsolete est ignoree.
    const requete = { annulee: false };
    void (async () => {
      const { data, response } = await api.GET('/traces/{correlation_id}', {
        params: { path: { correlation_id: correlationId } },
      });
      if (requete.annulee) return;
      if (data === undefined) {
        setEtat({ etape: 'erreur', message: `Chronologie indisponible (HTTP ${String(response.status)})` });
        return;
      }
      setEtat({ etape: 'pret', traces: data });
    })();
    return () => {
      requete.annulee = true;
    };
  }, [correlationId]);

  return (
    <section aria-labelledby="titre-chronologie" className="rounded-xl border border-slate-200 bg-white p-4">
      <h2 id="titre-chronologie" className="mb-3 text-sm font-medium text-slate-900">Chronologie</h2>
      {etat.etape === 'en-cours' && <p className="text-sm text-slate-500">Chargement...</p>}
      {etat.etape === 'erreur' && <p className="text-sm text-red-700">{etat.message}</p>}
      {etat.etape === 'pret' && etat.traces.length === 0 && (
        <p className="text-sm text-slate-500">Aucune trace pour ce dossier.</p>
      )}
      {etat.etape === 'pret' && etat.traces.length > 0 && (
        <ol className="space-y-2">
          {etat.traces.map((t, i) => {
            const echec = t.statut === 'ECHEC';
            return (
              <li
                key={t.source_id}
                className={`rounded-lg border px-3 py-2 ${echec ? 'border-red-200 bg-red-50' : 'border-slate-200'}`}
              >
                <div className="flex items-center justify-between gap-3 text-sm">
                  <span className="flex items-center gap-2">
                    <span className="w-8 font-mono text-xs text-slate-500">T{i + 1}</span>
                    <span className="text-slate-900">
                      {t.composant}.{t.operation}
                    </span>
                  </span>
                  <span className={`text-xs ${echec ? 'text-red-800' : 'text-emerald-800'}`}>{t.statut}</span>
                </div>
                <div className="ml-10 mt-0.5 text-xs text-slate-500">
                  {heure(t.debut)} · {t.duree_ms} ms
                </div>
                {echec && t.exception_type !== null && (
                  <div className="ml-10 mt-1 text-xs text-red-800">
                    {t.exception_type}
                    {t.exception_message !== null ? ` : ${t.exception_message}` : ''}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
