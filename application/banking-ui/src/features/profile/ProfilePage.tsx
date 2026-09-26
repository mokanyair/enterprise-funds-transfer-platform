import type { ReactNode } from "react";
import { useAuth } from "../../auth/AuthContext";
import { env } from "../../lib/env";

/**
 * Read-only: the backend exposes no profile endpoint, so this shows only what the identity
 * token carries. Password and MFA management stay with the identity provider (guide §15).
 */
export function ProfilePage() {
  const { user, logout } = useAuth();
  const accountConsoleUrl =
    env.authMode === "keycloak" && env.keycloak.url && env.keycloak.realm
      ? `${env.keycloak.url.replace(/\/$/, "")}/realms/${encodeURIComponent(env.keycloak.realm)}/account`
      : null;

  return (
    <div className="stack" style={{ maxWidth: 560, gap: "var(--space-3)" }}>
      <h1 className="page-title">Profile</h1>

      <div className="card stack">
        <h2 className="section-title">Your details</h2>
        <dl className="stack stack--tight">
          <Row label="Name" value={user?.name ?? "—"} />
          <Row label="Sign-in method" value={env.authMode === "keycloak" ? "Single sign-on" : "Development sign-in"} />
        </dl>
      </div>

      <div className="card stack">
        <h2 className="section-title">Security</h2>
        <p className="metadata">
          Your password and multi-factor authentication are managed by the identity provider, not this app.
        </p>
        {accountConsoleUrl ? (
          <a className="btn btn--secondary" href={accountConsoleUrl} style={{ alignSelf: "flex-start" }}>
            Manage sign-in and security
          </a>
        ) : (
          <p className="metadata">Not available in development sign-in.</p>
        )}
      </div>

      <button type="button" className="btn btn--danger" onClick={logout} style={{ alignSelf: "flex-start" }}>
        Sign out
      </button>
    </div>
  );
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="row" style={{ justifyContent: "space-between" }}>
      <dt className="metadata">{label}</dt>
      <dd style={{ margin: 0, fontWeight: 600 }}>{value}</dd>
    </div>
  );
}
