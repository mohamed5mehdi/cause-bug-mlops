import createClient from 'openapi-fetch';

import type { components, paths } from './schema';

/** Client HTTP type a partir du contrat OpenAPI d'aiops-engine (ADR-014).
 * Meme origine : les appels partent vers /api, que nginx (ou Vite en dev) relaie. */
export const api = createClient<paths>({ baseUrl: '/api' });

export type EtatSante = components['schemas']['EtatSante'];
