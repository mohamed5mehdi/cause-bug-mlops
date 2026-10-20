import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App';
import './index.css';

const racine = document.getElementById('root');
if (racine === null) {
  throw new Error("Element #root introuvable dans index.html");
}

createRoot(racine).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
