import { useCallback, useEffect, useState } from 'react';

import { api, type EtatSante } from '../api/client';

type Chargement =
  | { etape: 'en-cours' }
  | { etape: 'erreur'; message: string }
  | { etape: 'pret'; sante: EtatSante };

function Pastille({ libelle, ok }: { libelle: string; ok: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs ${
        ok ? 'bg-emerald-50 text-emerald-800' : 'bg-red-50 text-red-800'
      }`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${ok ? 'bg-emerald-600' : 'bg-red-600'}`} />
      {libelle}
    </span>
  );
}

/** Bandeau d'etat d'aiops-engine (GET /health). */
export default function EtatMoteur() {
  const [etat, setEtat] = useState<Chargement>({ etape: 'en-cours' });

  const charger = useCallback(async () => {
    const { data, response } = await api.GET('/health');
    if (data === undefined) {
      setEtat({ etape: 'erreur', message: `aiops-engine injoignable (HTTP ${String(response.status)})` });
      return;
    }
    setEtat({ etape: 'pret', sante: data });
  }, []);

  useEffect(() => {
    void charger();
  }, [charger]);

  if (etat.etape === 'en-cours') {
    return <p className="text-xs text-slate-500">Verification du moteur...</p>;
  }
  if (etat.etape === 'erreur') {
    return <Pastille libelle={etat.message} ok={false} />;
  }
  const s = etat.sante;
  return (
    <div className="flex flex-wrap gap-2">
      <Pastille libelle={`Moteur ${s.statut}`} ok={s.statut === 'UP'} />
      <Pastille libelle={s.base ? 'Base joignable' : 'Base injoignable'} ok={s.base} />
      <Pastille libelle={s.ecoute ? 'Ecoute des traces active' : 'Ecoute coupee'} ok={s.ecoute} />
      <Pastille
        libelle={`Vectorisation ${s.vectorisation === 'ACTIVE' ? 'active' : 'inactive'}`}
        ok={s.vectorisation === 'ACTIVE'}
      />
    </div>
  );
}
