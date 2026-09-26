import type { TokenSource } from "../api/http";

/** The slice of keycloak-js this module uses, so it can be tested without a real IdP. */
export interface KeycloakLike {
  token?: string;
  updateToken(minValiditySeconds: number): Promise<boolean>;
  login(): Promise<void> | void;
}

/** Refresh when the token has less than this many seconds left, before every request. */
export const MIN_TOKEN_VALIDITY_SECONDS = 30;

/**
 * Wraps keycloak-js so concurrent requests share one in-flight refresh, and a failed refresh
 * (refresh token expired, session ended in Keycloak) sends the user to sign in exactly once.
 */
export function createKeycloakTokenSource(keycloak: KeycloakLike): TokenSource {
  let inFlight: Promise<boolean> | null = null;
  let redirecting = false;

  function refresh(minValidity: number): Promise<boolean> {
    inFlight ??= keycloak
      .updateToken(minValidity)
      .then(() => true)
      .catch(() => false)
      .finally(() => {
        inFlight = null;
      });
    return inFlight;
  }

  function signIn() {
    if (redirecting) return;
    redirecting = true;
    void keycloak.login();
  }

  return {
    async getToken() {
      if (!(await refresh(MIN_TOKEN_VALIDITY_SECONDS))) {
        signIn();
        return null;
      }
      return keycloak.token ?? null;
    },
    forceRefresh: () => refresh(-1), // -1 forces a refresh regardless of remaining validity
    onUnauthenticated: signIn,
  };
}
