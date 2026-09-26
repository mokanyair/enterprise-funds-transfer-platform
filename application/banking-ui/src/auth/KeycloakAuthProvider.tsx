import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import Keycloak from "keycloak-js";
import { AuthContext } from "./AuthContext";
import { setAccessTokenGetter, setUnauthenticatedHandler } from "../api/http";
import { env } from "../lib/env";
import type { AuthState, AuthUser } from "./types";

/**
 * Authorization Code + PKCE against a real Keycloak realm, no client secret in the SPA
 * (Part II, Section 18 of the design guide). This path has not been exercised end to end —
 * there is no Keycloak instance in this environment to log in against — so verify the redirect
 * URI, web origins and realm/client config in a real environment before relying on it.
 */
export function KeycloakAuthProvider({ children }: { children: ReactNode }) {
  const keycloakRef = useRef<Keycloak | null>(null);
  const [status, setStatus] = useState<AuthState["status"]>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);

  useEffect(() => {
    const keycloak = new Keycloak({
      url: env.keycloak.url,
      realm: env.keycloak.realm,
      clientId: env.keycloak.clientId,
    });
    keycloakRef.current = keycloak;

    setAccessTokenGetter(() => keycloak.token ?? null);
    setUnauthenticatedHandler(() => {
      keycloak.login();
    });

    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => keycloak.login());
    };

    keycloak
      .init({ onLoad: "login-required", pkceMethod: "S256", checkLoginIframe: false })
      .then((authenticated) => {
        if (!authenticated) {
          setStatus("unauthenticated");
          return;
        }
        setUser({
          name:
            (keycloak.tokenParsed?.name as string | undefined) ??
            (keycloak.tokenParsed?.preferred_username as string | undefined) ??
            "Customer",
          subject: keycloak.tokenParsed?.sub ?? "",
        });
        setStatus("authenticated");
      })
      .catch(() => setStatus("unauthenticated"));
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      status,
      user,
      logout: () => keycloakRef.current?.logout({ redirectUri: window.location.origin }),
    }),
    [status, user],
  );

  if (status === "loading") {
    return <div className="centered-page">Signing you in…</div>;
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
