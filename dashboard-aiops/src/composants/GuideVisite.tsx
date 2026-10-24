import { useEffect, useRef, useState } from 'react';

type Etape = {
  /** Selecteur de l'element a mettre en lumiere ; null : bulle centree. */
  cible: string | null;
  titre: string;
  texte: string;
  /** L'etape attend un clic de l'utilisateur sur la cible au lieu d'un bouton Continuer. */
  attendClic?: boolean;
};

type Proprietes = {
  dossierP2: string | null;
  selection: string | null;
  onFermer: () => void;
};

function etapes(dossierP2: string | null): Etape[] {
  const debut: Etape[] = [
    {
      cible: null,
      titre: 'Bienvenue',
      texte:
        "Ce tableau de bord montre une IA qui retrouve la cause d'une panne dans un processus d'achats. 4 etapes, 30 secondes.",
    },
    {
      cible: '[data-tour="liste"]',
      titre: 'Les demandes d\'achat',
      texte:
        'Chaque ligne est une demande qui a traverse le circuit de validation. Le badge indique la panne injectee : verrou en base, calcul de TVA faux, etape contournee, ou demande saine.',
    },
  ];
  if (dossierP2 === null) return debut;
  return [
    ...debut,
    {
      cible: `[data-tour-dossier="${dossierP2}"]`,
      titre: 'A vous',
      texte: 'Cliquez sur cette demande marquee P2 · TVA.',
      attendClic: true,
    },
    {
      cible: '[data-tour="chronologie"]',
      titre: 'Ce que le systeme a fait',
      texte:
        'Chaque etape du circuit est tracee. Ici tout est en SUCCES, aucune erreur : pourtant le montant TTC est faux. C\'est ce que l\'IA doit trouver.',
    },
    {
      cible: '[data-tour="diagnostic"]',
      titre: 'Le diagnostic',
      texte:
        "Composant en cause, explication et preuves citees : T5 renvoie a une trace, R-05 a la regle de calcul de la TVA. Une preuve qui n'existe pas dans le dossier est rejetee automatiquement.",
    },
  ];
}

type Mesure = { cible: string; rect: DOMRect };

const LARGEUR = 320;
const MARGE = 16;

/** Guide pas a pas, a la maniere d'un tutoriel : une bulle par etape, l'element concerne mis en lumiere. */
export default function GuideVisite({ dossierP2, selection, onFermer }: Proprietes) {
  const liste = etapes(dossierP2);
  const [index, setIndex] = useState(0);
  const [mesure, setMesure] = useState<Mesure | null>(null);
  const bouton = useRef<HTMLButtonElement>(null);

  // L'etape "cliquez" se valide d'elle-meme quand le bon dossier est selectionne.
  const base = liste[index];
  const affiche = base?.attendClic === true && selection === dossierP2 ? index + 1 : index;
  const etape = liste[affiche];

  // Mesure de la cible (elle peut apparaitre apres un chargement), puis suivi du defilement.
  useEffect(() => {
    const cible = etape?.cible ?? null;
    if (cible === null) return;
    let annule = false;
    let essais = 0;
    let deplacee = false;
    const mesurer = () => {
      if (annule) return;
      const element = document.querySelector(cible);
      if (element === null) {
        essais += 1;
        if (essais < 30) window.setTimeout(mesurer, 100);
        return;
      }
      if (!deplacee) {
        deplacee = true;
        element.scrollIntoView({ block: 'nearest' });
      }
      setMesure({ cible, rect: element.getBoundingClientRect() });
    };
    const image = requestAnimationFrame(mesurer);
    window.addEventListener('resize', mesurer);
    window.addEventListener('scroll', mesurer, true);
    return () => {
      annule = true;
      cancelAnimationFrame(image);
      window.removeEventListener('resize', mesurer);
      window.removeEventListener('scroll', mesurer, true);
    };
  }, [etape?.cible]);

  useEffect(() => {
    bouton.current?.focus();
  }, [affiche]);

  useEffect(() => {
    const touche = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onFermer();
    };
    window.addEventListener('keydown', touche);
    return () => {
      window.removeEventListener('keydown', touche);
    };
  }, [onFermer]);

  if (etape === undefined) return null;

  const rect = etape.cible !== null && mesure?.cible === etape.cible ? mesure.rect : null;
  const derniere = affiche === liste.length - 1;

  let position: { top: number; left: number } | null = null;
  if (rect !== null) {
    const enDessous = rect.bottom + 220 < window.innerHeight;
    const top = enDessous ? rect.bottom + 12 : Math.max(MARGE, rect.top - 12 - 200);
    const left = Math.min(Math.max(MARGE, rect.left), window.innerWidth - LARGEUR - MARGE);
    position = { top, left };
  }

  return (
    <>
      {etape.cible === null ? (
        <div className="fixed inset-0 z-40 bg-slate-900/55" aria-hidden="true" />
      ) : (
        rect !== null && (
          <div
            aria-hidden="true"
            className="pointer-events-none fixed z-40 rounded-lg ring-2 ring-amber-400 motion-safe:transition-all motion-safe:duration-300"
            style={{
              top: rect.top - 6,
              left: rect.left - 6,
              width: rect.width + 12,
              height: rect.height + 12,
              boxShadow: '0 0 0 9999px rgb(15 23 42 / 0.55)',
            }}
          />
        )
      )}

      <div
        key={affiche}
        role="dialog"
        aria-labelledby="guide-titre"
        aria-describedby="guide-texte"
        className="apparition fixed z-50 rounded-lg border border-slate-200 bg-white p-4 shadow-lg"
        style={
          position === null
            ? // Centrage par marges : transform est reserve a l'animation d'apparition.
              { width: LARGEUR, top: '40%', left: '50%', marginLeft: -LARGEUR / 2 }
            : { width: LARGEUR, ...position }
        }
      >
        <p className="text-xs text-slate-500">
          Etape {affiche + 1} / {liste.length}
        </p>
        <h2 id="guide-titre" className="mt-1 text-sm font-medium text-slate-900">
          {etape.titre}
        </h2>
        <p id="guide-texte" className="mt-2 text-sm text-slate-700">
          {etape.texte}
        </p>

        <div className="mt-4 flex items-center justify-between">
          <button
            type="button"
            onClick={onFermer}
            className="rounded-md px-2 py-1 text-xs text-slate-500 hover:bg-slate-100"
          >
            Passer le guide
          </button>
          {etape.attendClic === true ? (
            <span className="text-xs text-amber-800">En attente de votre clic</span>
          ) : (
            <button
              ref={bouton}
              type="button"
              onClick={() => {
                if (derniere) onFermer();
                else setIndex(affiche + 1);
              }}
              className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-700"
            >
              {affiche === 0 ? 'Commencer' : derniere ? 'Terminer' : 'Continuer'}
            </button>
          )}
        </div>
      </div>
    </>
  );
}
