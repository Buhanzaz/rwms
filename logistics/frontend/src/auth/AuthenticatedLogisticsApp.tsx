import { LogIn } from 'lucide-react';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Button, EmptyState, Spinner } from '../components/ui';
import { beginPanelLogin, restorePanelUser } from './panel-oidc';

/** Browser session states that decide whether operational UI may be mounted. */
type AuthenticationState = 'checking' | 'redirecting' | 'authenticated' | 'unauthenticated';

function currentReturnPath(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

function SessionScreen({ children }: { children: ReactNode }) {
  return (
    <main className="app-shell" style={{ display: 'grid', minWidth: 0, placeItems: 'center', padding: 24 }}>
      {children}
    </main>
  );
}

/**
 * Gates the operational workspace on the primary RWMS USER session and starts an SSO redirect
 * when a separate browser tab has no copy of the panel's session-scoped token.
 */
export function AuthenticatedLogisticsApp({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthenticationState>('checking');
  const [loginFailed, setLoginFailed] = useState(false);

  const startLogin = useCallback((isCurrent: () => boolean = () => true) => {
    setState('redirecting');
    setLoginFailed(false);
    void beginPanelLogin(currentReturnPath()).catch(() => {
      if (!isCurrent()) return;
      setLoginFailed(true);
      setState('unauthenticated');
    });
  }, []);

  useEffect(() => {
    let current = true;
    void restorePanelUser()
      .then((user) => {
        if (!current) return;
        if (user) {
          setState('authenticated');
          return;
        }
        startLogin(() => current);
      })
      .catch(() => {
        if (current) startLogin(() => current);
      });
    return () => {
      current = false;
    };
  }, [startLogin]);

  if (state === 'authenticated') return children;
  if (state === 'checking' || state === 'redirecting') {
    return (
      <SessionScreen>
        <Spinner label={state === 'checking' ? 'Проверяем вход в RWMS' : 'Открываем вход в RWMS'} />
      </SessionScreen>
    );
  }
  return (
    <SessionScreen>
      <EmptyState
        icon={<LogIn size={22} aria-hidden="true" />}
        title="Требуется вход в RWMS"
        description={loginFailed
          ? 'Не удалось открыть вход. Проверьте соединение и повторите попытку.'
          : 'Логистика доступна только пользователям основной панели RWMS.'}
        action={(
          <Button type="button" variant="primary" onClick={() => startLogin()}>
            <LogIn size={15} aria-hidden="true" />
            Войти в RWMS
          </Button>
        )}
      />
    </SessionScreen>
  );
}
