import { useCallback, useEffect, useState } from 'react';

import { api, type EtatSante } from './api/client';

/** Etat de l'ecran : union discriminee (equivalent d'une sealed interface Java). */
type Chargement =
  | { etape: 'en-cours' }
  | { etape: 'erreur'; message: string }
  | { etape: 'pret'; sante: EtatSante };

function Indicateur({ libelle, ok, valeur }: { libelle: string; ok: boolean; valeur: string }) {
  return (
    <div className="flex items-center justify-between rounded-lg border border-slate-200 bg-white px-4 py-3">
      <span className="text-sm text-slate-600">{libelle}</span>
      <span className={`text-sm font-medium ${ok ? 'text-emerald-700' : 'text-red-700'}`}>{valeur}</span>
    </div>
  );
}

export default function App() {
  const [etat, setEtat] = useState<Chargement>({ etape: 'en-cours' });

  const charger = useCallback(async () => {
    setEtat({ etape: 'en-cours' });
    const { data, error, response } = await api.GET('/health');
    if (data === undefined) {
      setEtat({ etape: 'erreur', message: `aiops-engine injoignable (HTTP ${String(response.status)})` });
      console.warn('Echec /api/health', error);
      return;
    }
    setEtat({ etape: 'pret', sante: data });
  }, []);

  useEffect(() => {
    void charger();
  }, [charger]);

  return (
    <main className="mx-auto max-w-3xl px-4 py-10">
      <header className="mb-8">
        <h1 className="text-2xl font-medium text-slate-900">Cause.Bug, tableau de bord AIOps</h1>
        <p className="mt-1 text-sm text-slate-600">
          Diagnostic de cause racine sur le simulateur de processus d'achats.
        </p>
      </header>

      <section aria-labelledby="titre-sante" className="rounded-xl bg-slate-50 p-5">
        <div className="mb-4 flex items-center justify-between">
          <h2 id="titre-sante" className="text-base font-medium text-slate-900">Etat du moteur</h2>
          <button
            type="button"
            onClick={() => void charger()}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-white"
          >
            Actualiser
          </button>
        </div>

        {etat.etape === 'en-cours' && <p className="text-sm text-slate-600">Chargement...</p>}

        {etat.etape === 'erreur' && <p className="text-sm text-red-700">{etat.message}</p>}

        {etat.etape === 'pret' && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Indicateur libelle="Statut" ok={etat.sante.statut === 'UP'} valeur={etat.sante.statut} />
            <Indicateur libelle="Base de donnees" ok={etat.sante.base} valeur={etat.sante.base ? 'joignable' : 'injoignable'} />
            <Indicateur libelle="Ecoute des traces" ok={etat.sante.ecoute} valeur={etat.sante.ecoute ? 'active' : 'coupee'} />
            <Indicateur libelle="Traces ingerees" ok valeur={String(etat.sante.traces_ingerees)} />
            <Indicateur
              libelle="Vectorisation"
              ok={etat.sante.vectorisation === 'ACTIVE'}
              valeur={etat.sante.vectorisation === 'ACTIVE' ? 'active' : 'inactive'}
            />
            <Indicateur libelle="Documents vectorises" ok valeur={String(etat.sante.documents_vectorises)} />
          </div>
        )}
      </section>
    </main>
  );
}
