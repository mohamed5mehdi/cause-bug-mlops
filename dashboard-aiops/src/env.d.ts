/** Variables d'environnement lues au build par Vite. */
interface ImportMetaEnv {
  /** "true" : build de la demo statique (donnees enregistrees, aucun appel a l'API). */
  readonly VITE_DEMO?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
