import { useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../../auth/AuthContext";
import { useAccounts } from "../../hooks/useAccounts";
import { useAccountHistory } from "../../hooks/useAccountHistory";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { StatusBadge } from "../../components/StatusBadge";
import { SkeletonCard, Skeleton } from "../../components/Skeleton";
import { EmptyState } from "../../components/EmptyState";
import { ErrorPanel } from "../../components/ErrorPanel";
import { TransferTable } from "../../components/TransferTable";
import { maskAccountId } from "../../lib/format";

export function DashboardPage() {
  const { user } = useAuth();
  const accountsQuery = useAccounts();
  const accounts = accountsQuery.data?.accounts ?? [];
  const [activeAccountId, setActiveAccountId] = useState<string | undefined>();
  const selectedAccountId = activeAccountId ?? accounts[0]?.accountId;
  const historyQuery = useAccountHistory(selectedAccountId, { page: 0, size: 5 });

  return (
    <div className="stack" style={{ gap: "var(--space-4)" }}>
      <div>
        <h1 className="page-title">Welcome back{user ? `, ${user.name.split(" ")[0]}` : ""}</h1>
        <p className="metadata">Here's what's happening across your accounts.</p>
      </div>

      <div className="row">
        <Link to="/transfers/new" className="btn btn--primary">
          Transfer money
        </Link>
        <Link to="/accounts" className="btn btn--secondary">
          View accounts
        </Link>
        <Link to="/transactions" className="btn btn--secondary">
          View transactions
        </Link>
      </div>

      <section className="stack">
        <h2 className="section-title">Your accounts</h2>
        {accountsQuery.isLoading && (
          <div className="grid-cards">
            <SkeletonCard />
            <SkeletonCard />
          </div>
        )}
        {accountsQuery.isError && <ErrorPanel error={accountsQuery.error} onRetry={() => accountsQuery.refetch()} />}
        {accountsQuery.isSuccess && accounts.length === 0 && (
          <EmptyState title="No accounts on file" description="Contact support if you believe this is incorrect." />
        )}
        {accountsQuery.isSuccess && accounts.length > 0 && (
          <div className="grid-cards">
            {accounts.map((account) => (
              <Link key={account.accountId} to={`/accounts/${account.accountId}`} className="card card--interactive stack">
                <div className="row" style={{ justifyContent: "space-between" }}>
                  <span className="card-title">
                    <MaskedAccountNumber accountId={account.accountId} />
                  </span>
                  <StatusBadge status={account.status} />
                </div>
                <span className="balance-figure">
                  <CurrencyDisplay amount={account.availableBalance} currency={account.currency} />
                </span>
                <span className="metadata">Available balance</span>
              </Link>
            ))}
          </div>
        )}
      </section>

      {accounts.length > 0 && (
        <section className="stack">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <h2 className="section-title">Recent activity</h2>
            {accounts.length > 1 && (
              <select
                className="select"
                value={selectedAccountId}
                onChange={(e) => setActiveAccountId(e.target.value)}
                aria-label="Account for recent activity"
              >
                {accounts.map((a) => (
                  <option key={a.accountId} value={a.accountId}>
                    {maskAccountId(a.accountId)}
                  </option>
                ))}
              </select>
            )}
          </div>
          {historyQuery.isLoading && <Skeleton height="180px" />}
          {historyQuery.isError && <ErrorPanel error={historyQuery.error} onRetry={() => historyQuery.refetch()} />}
          {historyQuery.isSuccess && (
            <TransferTable transfers={historyQuery.data.items} perspectiveAccountId={selectedAccountId} />
          )}
        </section>
      )}
    </div>
  );
}
