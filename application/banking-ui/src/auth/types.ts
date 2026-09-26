export interface AuthUser {
  name: string;
  subject: string;
}

export type AuthStatus = "loading" | "authenticated" | "unauthenticated";

export interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  /** Dev mode only: lets the mock sign-in screen pick who "you" are. No-op under Keycloak. */
  loginAs?: (user: AuthUser) => void;
  logout: () => void;
}
