import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useAccountHistory } from "../../hooks/useAccountHistory";
import { TRANSFER_STATUSES, type TransferStatus } from "../../api/types";
import { TransferTable } from "../../components/TransferTable";
import { Pagination } from "../../components/Pagination";
import { Skeleton } from "../../components/Skeleton";
import { ErrorPanel } from "../../components/ErrorPanel";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";

const PAGE_SIZE = 20;

export function AccountTransactionsPage() {
  const { accountId } = useParams<{ accountId: string }>();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<TransferStatus | "">("");

  const historyQuery = useAccountHistory(accountId, {
    page,
    size: PAGE_SIZE,
    status: status || undefined,
  });

  return (
    <div className="stack" style={{ gap: "var(--space-3)" }}>
      <div>
        <p className="metadata">
          <Link to="/transactions">Transactions</Link> / <MaskedAccountNumber accountId={accountId ?? ""} />
        </p>
        <h1 className="page-title">Transaction history</h1>
      </div>

      <div className="row">
        <div className="field" style={{ minWidth: 200 }}>
          <label htmlFor="status-filter">Status</label>
          <select
            id="status-filter"
            className="select"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as TransferStatus | "");
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            {TRANSFER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>

      {historyQuery.isLoading && <Skeleton height="320px" />}
      {historyQuery.isError && <ErrorPanel error={historyQuery.error} onRetry={() => historyQuery.refetch()} />}
      {historyQuery.isSuccess && (
        <>
          <TransferTable transfers={historyQuery.data.items} perspectiveAccountId={accountId} />
          <Pagination page={page} totalPages={historyQuery.data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}
