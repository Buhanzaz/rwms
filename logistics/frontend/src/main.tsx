import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import 'maplibre-gl/dist/maplibre-gl.css';
import './styles.css';
import { App } from './app/App';
import { AuthenticatedLogisticsApp } from './auth/AuthenticatedLogisticsApp';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, staleTime: 15_000, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
});

const root = document.getElementById('root');
if (!root) throw new Error('Не найден корневой элемент приложения');

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthenticatedLogisticsApp>
        <App />
      </AuthenticatedLogisticsApp>
    </QueryClientProvider>
  </StrictMode>,
);
