import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { registerSW } from 'virtual:pwa-register';
import { createQueryClient } from './api/queryClient';
import { routes } from './router';
import './index.css';

const queryClient = createQueryClient();
const router = createBrowserRouter(routes);

// registerType is 'prompt': the new version waits until the user accepts it (UI arrives in M4).
registerSW();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
