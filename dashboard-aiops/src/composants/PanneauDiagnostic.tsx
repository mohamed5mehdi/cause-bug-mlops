import { useCallback, useEffect, useState } from 'react';

import {
  api,
  type DiagnosticHistorique,
  type DiagnosticReponse,
  messageErreurDiagnostic,
} from '../api/client';
import { LIBELLE_COMPOSANT, dateHeure, pourcentage } from '../format';

/** Etat du diagnostic en cours : une seule demande a la fois (pas d'appel Gemini paye deux fois). */
type Diagnostic =
  | { etape: 'repos' }
  | { etape: 'en-cours'; depuis: number }
  | { etape: 'erreur'; message: string }
  | { etape: 'pret'; resultat: DiagnosticReponse };

function Verdict({ d }: { d: DiagnosticReponse }) {
  return (
    <div className="rounded-lg border border-slate-200 p-4">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span className="rounded-full bg-violet-50 px-2.5 py-1 text-xs text-violet-800">
          {LIBELLE_COMPOSANT[d.composant]}
        </span>
        <span className="text-xs text-slate-600">Confiance {pourcentage(d.confiance)}</span>
      </div>
      <p className="text-sm leading-relaxed text-slate-900">{d.cause_racine}</p>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {d.preuves.map((p) => (
          <span key={p} className="rounded bg-slate-100 px-2 py-0.5 font-mono text-xs text-slate-700">{p}</span>
        ))}
      </div>
      {d.preuves_rejetees.length > 0 && (
        <p className="mt-2 text-xs text-amber-800">
          Preuves inventees retirees : {d.preuves_rejetees.join(', ')}
        </p>
      )}
      <p className="mt-3 text-xs text-slate-500">
        {d.modele} · {(d.latence_ms / 1000).toFixed(1)} s · {d.contexte.traces} traces,{' '}
        {d.contexte.incidents_similaires} incidents proches, {d.contexte.regles_metier} regles
      </p>
    </div>
  );
}

/** Lance un diagnostic (POST /diagnose) et montre l'historique (GET /diagnostics). */
export default function PanneauDiagnostic({ correlationId }: { correlationId: string }) {
  const [diagnostic, setDiagnostic] = useState<Diagnostic>({ etape: 'repos' });
  const [historique, setHistorique] = useState<DiagnosticHistorique[]>([]);
  const [secondes, setSecondes] = useState(0);

  const chargerHistorique = useCallback(async () => {
    const { data } = await api.GET('/diagnostics/{correlation_id}', {
      params: { path: { correlation_id: correlationId } },
    });
    if (data !== undefined) setHistorique(data);
  }, [correlationId]);

  useEffect(() => {
    void chargerHistorique();
  }, [chargerHistorique]);

  // Chronometre visible pendant l'attente (jusqu'a 130 s, ADR-015).
  useEffect(() => {
    if (diagnostic.etape !== 'en-cours') return;
    const depuis = diagnostic.depuis;
    const minuterie = window.setInterval(() => {
      setSecondes(Math.floor((Date.now() - depuis) / 1000));
    }, 1000);
    return () => {
      window.clearInterval(minuterie);
    };
  }, [diagnostic]);

  const lancer = async () => {
    setSecondes(0);
    setDiagnostic({ etape: 'en-cours', depuis: Date.now() });
    const { data, response } = await api.POST('/diagnose/{correlation_id}', {
      params: { path: { correlation_id: correlationId } },
    });
    if (data === undefined) {
      setDiagnostic({ etape: 'erreur', message: messageErreurDiagnostic(response.status) });
      return;
    }
    setDiagnostic({ etape: 'pret', resultat: data });
    void chargerHistorique();
  };

  const enCours = diagnostic.etape === 'en-cours';

  return (
    <section aria-labelledby="titre-diagnostic" className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-3 flex items-center justify-between">
        <h2 id="titre-diagnostic" className="text-sm font-medium text-slate-900">Diagnostic de cause racine</h2>
        <button
          type="button"
          disabled={enCours}
          onClick={() => void lancer()}
          className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-700 disabled:cursor-wait disabled:bg-slate-400"
        >
          {enCours ? `Analyse en cours... ${String(secondes)} s` : 'Diagnostiquer'}
        </button>
      </div>

      {diagnostic.etape === 'repos' && historique.length === 0 && (
        <p className="text-sm text-slate-500">Aucun diagnostic pour ce dossier.</p>
      )}
      {enCours && (
        <p className="text-sm text-slate-600">
          Recherche des traces, des incidents proches et des regles metier, puis analyse par Gemini.
        </p>
      )}
      {diagnostic.etape === 'erreur' && <p className="text-sm text-red-700">{diagnostic.message}</p>}
      {diagnostic.etape === 'pret' && <Verdict d={diagnostic.resultat} />}

      {historique.length > 0 && (
        <div className="mt-4">
          <h3 className="mb-2 text-xs font-medium text-slate-600">Historique ({historique.length})</h3>
          <ul className="space-y-1.5">
            {historique.map((h) => (
              <li key={h.id} className="flex items-start justify-between gap-3 text-xs">
                <span className="text-slate-800">
                  <span className="mr-2 text-slate-500">{LIBELLE_COMPOSANT[h.composant]}</span>
                  {h.cause_racine}
                </span>
                <span className="shrink-0 text-slate-500">
                  {pourcentage(h.confiance)} · {dateHeure(h.cree_le)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
