import { Link } from "react-router-dom";
import { useAccounts } from "../../hooks/useAccounts";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { EmptyState } from "../../components/EmptyState";
import { ErrorPanel } from "../../components/ErrorPanel";
import { SkeletonCard } from "../../components/Skeleton";

/** No cross-account transaction feed exists on the backend, so pick an account first. */
export function TransactionsPage() {
  const accountsQuery = useAccounts();
  const accounts = accountsQuery.data?.accounts ?? [];

  return (
    <div className="stack" style={{ gap: "var(--space-3)" }}>
      <h1 className="page-title">Transactions</h1>
      <p className="metadata">Choose an account to see its transaction history.</p>

      {accountsQuery.isLoading && (
        <div className="grid-cards">
          <SkeletonCard />
          <SkeletonCard />
        </div>
      )}
      {accountsQuery.isError && <ErrorPanel error={accountsQuery.error} onRetry={() => accountsQuery.refetch()} />}
      {accountsQuery.isSuccess && accounts.length === 0 && <EmptyState title="No accounts on file" />}
      {accountsQuery.isSuccess && accounts.length > 0 && (
        <div className="grid-cards">
          {accounts.map((account) => (
            <Link key={account.accountId} to={`/transactions/${account.accountId}`} className="card card--interactive stack">
              <span className="card-title">
                <MaskedAccountNumber accountId={account.accountId} />
              </span>
              <span className="metadata">
                Available: <CurrencyDisplay amount={account.availableBalance} currency={account.currency} />
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
