import { Link, useParams } from "react-router-dom";
import { useAccountBalance } from "../../hooks/useAccountBalance";
import { useAccountHistory } from "../../hooks/useAccountHistory";
import { useAccounts } from "../../hooks/useAccounts";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { Skeleton } from "../../components/Skeleton";
import { ErrorPanel } from "../../components/ErrorPanel";
import { TransferTable } from "../../components/TransferTable";

export function AccountDetailPage() {
  const { accountId } = useParams<{ accountId: string }>();
  const balanceQuery = useAccountBalance(accountId);
  const historyQuery = useAccountHistory(accountId, { page: 0, size: 10 });
  // Balance has no status field; the account list does. Until it loads, no transfer action is offered.
  const account = useAccounts().data?.accounts.find((a) => a.accountId === accountId);

  return (
    <div className="stack" style={{ gap: "var(--space-3)" }}>
      <div>
        <p className="metadata">
          <Link to="/accounts">Accounts</Link> / <MaskedAccountNumber accountId={accountId ?? ""} />
        </p>
        <h1 className="page-title">
          <MaskedAccountNumber accountId={accountId ?? ""} />
        </h1>
      </div>

      <div className="grid-cards">
        <div className="card stack">
          <span className="metadata">Available balance</span>
          {balanceQuery.isLoading && <Skeleton width="180px" height="34px" />}
          {balanceQuery.isError && <ErrorPanel error={balanceQuery.error} onRetry={() => balanceQuery.refetch()} />}
          {balanceQuery.isSuccess && (
            <span className="balance-figure">
              <CurrencyDisplay amount={balanceQuery.data.availableBalance} currency={balanceQuery.data.currency} />
            </span>
          )}
        </div>
        <div className="card stack">
          <span className="metadata">Current (ledger) balance</span>
          {balanceQuery.isLoading && <Skeleton width="180px" height="34px" />}
          {balanceQuery.isSuccess && (
            <span className="balance-figure">
              <CurrencyDisplay amount={balanceQuery.data.ledgerBalance} currency={balanceQuery.data.currency} />
            </span>
          )}
        </div>
      </div>

      {account?.status === "ACTIVE" && (
        <div className="row">
          <Link to={`/transfers/new?from=${encodeURIComponent(accountId ?? "")}`} className="btn btn--primary">
            Transfer from this account
          </Link>
        </div>
      )}
      {account && account.status !== "ACTIVE" && (
        <div className="alert alert--warning" role="status">
          This account is {account.status === "FROZEN" ? "frozen" : "closed"}, so it can't send transfers.
        </div>
      )}

      <section className="stack">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2 className="section-title">Recent activity</h2>
          <Link to={`/transactions/${accountId}`} className="metadata">
            View all
          </Link>
        </div>
        {historyQuery.isLoading && <Skeleton height="220px" />}
        {historyQuery.isError && <ErrorPanel error={historyQuery.error} onRetry={() => historyQuery.refetch()} />}
        {historyQuery.isSuccess && (
          <TransferTable transfers={historyQuery.data.items} perspectiveAccountId={accountId} />
        )}
      </section>
    </div>
  );
}
