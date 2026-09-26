import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { queryClient } from "./app/queryClient";
import { env } from "./lib/env";
import { DevAuthProvider } from "./auth/DevAuthProvider";
import { KeycloakAuthProvider } from "./auth/KeycloakAuthProvider";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { AppShell } from "./components/AppShell";
import { LoginPage } from "./features/login/LoginPage";
import { DashboardPage } from "./features/dashboard/DashboardPage";
import { AccountsPage } from "./features/accounts/AccountsPage";
import { AccountDetailPage } from "./features/accounts/AccountDetailPage";
import { NewTransferPage } from "./features/transfers/NewTransferPage";
import { TransferDetailPage } from "./features/transfers/TransferDetailPage";
import { TransactionsPage } from "./features/transactions/TransactionsPage";
import { AccountTransactionsPage } from "./features/transactions/AccountTransactionsPage";
import { ProfilePage } from "./features/profile/ProfilePage";

const AuthProvider = env.authMode === "keycloak" ? KeycloakAuthProvider : DevAuthProvider;

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route element={<ProtectedRoute />}>
              <Route element={<AppShell />}>
                <Route path="/" element={<Navigate to="/dashboard" replace />} />
                <Route path="/dashboard" element={<DashboardPage />} />
                <Route path="/accounts" element={<AccountsPage />} />
                <Route path="/accounts/:accountId" element={<AccountDetailPage />} />
                <Route path="/transfers/new" element={<NewTransferPage />} />
                <Route path="/transfers/:transferId" element={<TransferDetailPage />} />
                <Route path="/transactions" element={<TransactionsPage />} />
                <Route path="/transactions/:accountId" element={<AccountTransactionsPage />} />
                <Route path="/profile" element={<ProfilePage />} />
                <Route path="*" element={<Navigate to="/dashboard" replace />} />
              </Route>
            </Route>
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
