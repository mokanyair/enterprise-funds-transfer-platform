import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../../auth/AuthContext";
import { env } from "../../lib/env";

const DEMO_CUSTOMERS = [
  { name: "Jordan Reyes", subject: "customer-1" },
  { name: "Priya Natarajan", subject: "customer-2" },
];

export function LoginPage() {
  const { status, loginAs } = useAuth();
  const location = useLocation();

  if (status === "authenticated") {
    const from = (location.state as { from?: Location })?.from;
    return <Navigate to={from?.pathname ?? "/dashboard"} replace />;
  }

  // Under VITE_AUTH_MODE=keycloak, ProtectedRoute never renders this page: the Keycloak
  // provider redirects straight to the hosted login page instead.
  return (
    <div className="centered-page">
      <div className="card auth-card stack">
        <div className="stack stack--tight">
          <h1 className="page-title">
            Nova<span className="brand__accent">Bank</span>
          </h1>
          <p className="metadata">Development sign-in — no password, mock data only.</p>
        </div>
        {env.useMocks || (
          <div className="alert alert--warning">
            VITE_AUTH_MODE=dev with VITE_USE_MOCKS=false: signing in here will not produce a token the real
            backend accepts.
          </div>
        )}
        <div className="stack">
          {DEMO_CUSTOMERS.map((customer) => (
            <button
              key={customer.subject}
              type="button"
              className="btn btn--primary"
              onClick={() => loginAs?.(customer)}
            >
              Continue as {customer.name}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
