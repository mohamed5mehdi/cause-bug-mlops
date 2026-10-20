import { useCallback, useEffect, useState } from 'react';

import { api, type DossierResume } from '../api/client';
import { dateHeure, idCourt } from '../format';

type Proprietes = {
  selection: string | null;
  onSelection: (correlationId: string) => void;
};

type Chargement =
  | { etape: 'en-cours' }
  | { etape: 'erreur'; message: string }
  | { etape: 'pret'; dossiers: DossierResume[] };

/** Registre des dossiers les plus recemment actifs (GET /dossiers). */
export default function ListeDossiers({ selection, onSelection }: Proprietes) {
  const [etat, setEtat] = useState<Chargement>({ etape: 'en-cours' });

  const charger = useCallback(async () => {
    setEtat({ etape: 'en-cours' });
    const { data, response } = await api.GET('/dossiers', { params: { query: { limite: 50 } } });
    if (data === undefined) {
      setEtat({ etape: 'erreur', message: `Liste indisponible (HTTP ${String(response.status)})` });
      return;
    }
    setEtat({ etape: 'pret', dossiers: data });
  }, []);

  useEffect(() => {
    void charger();
  }, [charger]);

  return (
    <section aria-labelledby="titre-dossiers" className="rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
        <h2 id="titre-dossiers" className="text-sm font-medium text-slate-900">Dossiers</h2>
        <button
          type="button"
          onClick={() => void charger()}
          className="rounded-md px-2 py-1 text-xs text-slate-600 hover:bg-slate-100"
        >
          Actualiser
        </button>
      </div>

      {etat.etape === 'en-cours' && <p className="px-4 py-3 text-sm text-slate-500">Chargement...</p>}
      {etat.etape === 'erreur' && <p className="px-4 py-3 text-sm text-red-700">{etat.message}</p>}
      {etat.etape === 'pret' && etat.dossiers.length === 0 && (
        <p className="px-4 py-3 text-sm text-slate-500">
          Aucun dossier : creez une demande dans le simulateur d'achats.
        </p>
      )}
      {etat.etape === 'pret' && etat.dossiers.length > 0 && (
        <ul className="max-h-[70vh] divide-y divide-slate-100 overflow-y-auto">
          {etat.dossiers.map((d) => {
            const actif = d.business_correlation_id === selection;
            return (
              <li key={d.business_correlation_id}>
                <button
                  type="button"
                  aria-current={actif}
                  onClick={() => { onSelection(d.business_correlation_id); }}
                  className={`w-full px-4 py-3 text-left hover:bg-slate-50 ${actif ? 'bg-slate-100' : ''}`}
                >
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-sm text-slate-900">{idCourt(d.business_correlation_id)}</span>
                    {d.nb_echecs > 0 ? (
                      <span className="rounded-full bg-red-50 px-2 py-0.5 text-xs text-red-800">
                        {d.nb_echecs} echec{d.nb_echecs > 1 ? 's' : ''}
                      </span>
                    ) : (
                      <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs text-emerald-800">sans echec</span>
                    )}
                  </div>
                  <div className="mt-1 text-xs text-slate-500">
                    {d.nb_traces} trace{d.nb_traces > 1 ? 's' : ''} · {dateHeure(d.derniere_trace)}
                  </div>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
