import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { env } from "../lib/env";

const NAV_ITEMS = [
  { to: "/dashboard", label: "Dashboard", icon: "⌂" },
  { to: "/accounts", label: "Accounts", icon: "▤" },
  { to: "/transfers/new", label: "Transfer", icon: "⇄" },
  { to: "/transactions", label: "Transactions", icon: "≡" },
];

function navClassName({ isActive }: { isActive: boolean }) {
  return `nav-link${isActive ? " nav-link--active" : ""}`;
}

function bottomNavClassName({ isActive }: { isActive: boolean }) {
  return `bottomnav-link${isActive ? " bottomnav-link--active" : ""}`;
}

export function AppShell() {
  const { user, logout } = useAuth();

  return (
    <div className="app-shell">
      {env.useMocks && <div className="mock-banner">Development data — not connected to a real account</div>}
      <aside className="app-shell__sidebar" aria-label="Primary navigation">
        <div className="brand">
          Nova<span className="brand__accent">Bank</span>
        </div>
        <ul className="nav-list">
          {NAV_ITEMS.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} className={navClassName}>
                <span aria-hidden="true">{item.icon}</span>
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </aside>

      <header className="app-shell__topbar">
        <span className="section-title" style={{ fontSize: 16 }}>
          {env.useMocks ? "Development" : "NovaBank"}
        </span>
        <div className="row" style={{ gap: 12 }}>
          <NavLink to="/profile" className="metadata" aria-label="Profile">
            {user?.name ?? "Profile"}
          </NavLink>
          <button type="button" className="btn btn--ghost" onClick={logout}>
            Sign out
          </button>
        </div>
      </header>

      <main className="app-shell__main" id="main-content">
        <Outlet />
      </main>

      <nav className="app-shell__bottomnav" aria-label="Primary navigation">
        {NAV_ITEMS.map((item) => (
          <NavLink key={item.to} to={item.to} className={bottomNavClassName}>
            <span aria-hidden="true">{item.icon}</span>
            {item.label}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
