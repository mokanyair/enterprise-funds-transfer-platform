import { Link } from "react-router-dom";
import { useAccounts } from "../../hooks/useAccounts";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { StatusBadge } from "../../components/StatusBadge";
import { SkeletonCard } from "../../components/Skeleton";
import { EmptyState } from "../../components/EmptyState";
import { ErrorPanel } from "../../components/ErrorPanel";

export function AccountsPage() {
  const accountsQuery = useAccounts();
  const accounts = accountsQuery.data?.accounts ?? [];

  return (
    <div className="stack" style={{ gap: "var(--space-3)" }}>
      <h1 className="page-title">Accounts</h1>

      {accountsQuery.isLoading && (
        <div className="grid-cards">
          <SkeletonCard />
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
              <span className="metadata">
                Available · Current: <CurrencyDisplay amount={account.ledgerBalance} currency={account.currency} />
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
