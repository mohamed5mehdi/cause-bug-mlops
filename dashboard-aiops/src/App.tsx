import { useCallback, useEffect, useState } from 'react';

import { MODE_DEMO } from './api/demo';
import { chargerScenarios, type Scenarios } from './api/scenarios';
import Chronologie from './composants/Chronologie';
import EtatMoteur from './composants/EtatMoteur';
import GuideVisite from './composants/GuideVisite';
import ListeDossiers from './composants/ListeDossiers';
import PanneauDiagnostic from './composants/PanneauDiagnostic';

const CLE_GUIDE_VU = 'causebug-guide-vu';

/** Le guide s'ouvre a la premiere visite de la demo ; le stockage peut etre indisponible. */
function guideDejaVu(): boolean {
  try {
    return window.localStorage.getItem(CLE_GUIDE_VU) === '1';
  } catch {
    return false;
  }
}

function marquerGuideVu(): void {
  try {
    window.localStorage.setItem(CLE_GUIDE_VU, '1');
  } catch {
    // Sans stockage, le guide se rouvrira a la prochaine visite : sans consequence.
  }
}

export default function App() {
  const [selection, setSelection] = useState<string | null>(null);
  const [scenarios, setScenarios] = useState<Scenarios>({});
  const [guideOuvert, setGuideOuvert] = useState(() => MODE_DEMO && !guideDejaVu());
  const [cleGuide, setCleGuide] = useState(0);

  useEffect(() => {
    void chargerScenarios().then(setScenarios);
  }, []);

  const fermerGuide = useCallback(() => {
    setGuideOuvert(false);
    marquerGuideVu();
  }, []);

  const dossierP2 = Object.entries(scenarios).find(([, cas]) => cas === 'P2')?.[0] ?? null;

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-medium text-slate-900">Cause.Bug, tableau de bord AIOps</h1>
          <p className="mt-1 text-sm text-slate-600">
            Diagnostic de cause racine sur le simulateur de processus d'achats.
          </p>
        </div>
        {MODE_DEMO ? (
          <button
            type="button"
            onClick={() => {
              setCleGuide((c) => c + 1);
              setGuideOuvert(true);
            }}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
          >
            Revoir le guide
          </button>
        ) : (
          <EtatMoteur />
        )}
      </header>

      {MODE_DEMO && (
        <p className="mb-6 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          Demo statique : pannes injectees et diagnostics reels, enregistres lors de la seconde
          mesure. Aucun appel au modele depuis cette page. Pour lancer le pipeline complet, voir le{' '}
          <a
            className="underline"
            href="https://github.com/mohamed5mehdi/cause-bug-mlops#run-it"
          >
            README
          </a>
          .
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
        <ListeDossiers selection={selection} onSelection={setSelection} scenarios={scenarios} />

        {selection === null ? (
          <p className="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500">
            Choisissez un dossier pour voir sa chronologie et son diagnostic.
          </p>
        ) : (
          // key : un nouveau dossier repart d'un etat vierge (pas de diagnostic d'un autre dossier affiche).
          <div key={selection} className="apparition space-y-6">
            <div data-tour="chronologie">
              <Chronologie correlationId={selection} />
            </div>
            <div data-tour="diagnostic">
              <PanneauDiagnostic correlationId={selection} />
            </div>
          </div>
        )}
      </div>

      {guideOuvert && (
        <GuideVisite key={cleGuide} dossierP2={dossierP2} selection={selection} onFermer={fermerGuide} />
      )}
    </main>
  );
}
