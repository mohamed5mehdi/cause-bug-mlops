import { useState } from 'react';

import Chronologie from './composants/Chronologie';
import EtatMoteur from './composants/EtatMoteur';
import ListeDossiers from './composants/ListeDossiers';
import PanneauDiagnostic from './composants/PanneauDiagnostic';

export default function App() {
  const [selection, setSelection] = useState<string | null>(null);

  return (
    <main className="mx-auto max-w-6xl px-4 py-8">
      <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-medium text-slate-900">Cause.Bug, tableau de bord AIOps</h1>
          <p className="mt-1 text-sm text-slate-600">
            Diagnostic de cause racine sur le simulateur de processus d'achats.
          </p>
        </div>
        <EtatMoteur />
      </header>

      <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
        <ListeDossiers selection={selection} onSelection={setSelection} />

        {selection === null ? (
          <p className="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500">
            Choisissez un dossier pour voir sa chronologie et lancer un diagnostic.
          </p>
        ) : (
          // key : un nouveau dossier repart d'un etat vierge (pas de diagnostic d'un autre dossier affiche).
          <div key={selection} className="space-y-6">
            <Chronologie correlationId={selection} />
            <PanneauDiagnostic correlationId={selection} />
          </div>
        )}
      </div>
    </main>
  );
}
