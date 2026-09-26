import { useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { useCancelTransfer, useTransfer } from "../../hooks/useTransfer";
import { isCancellableStatus, isTerminalStatus } from "../../api/types";
import { StatusBadge } from "../../components/StatusBadge";
import { CurrencyDisplay } from "../../components/CurrencyDisplay";
import { MaskedAccountNumber } from "../../components/MaskedAccountNumber";
import { Skeleton } from "../../components/Skeleton";
import { ErrorPanel } from "../../components/ErrorPanel";

const STATUS_EXPLANATION: Record<string, string> = {
  RECEIVED: "Your transfer has been received and is being validated.",
  VALIDATING: "Your transfer is being validated.",
  PENDING_REVIEW: "This transfer needs manual review before it can proceed. This can take longer than usual.",
  PROCESSING: "Your transfer is being posted to both accounts.",
  COMPLETED: "This transfer has completed.",
  REJECTED: "This transfer was rejected.",
  // The API contract doesn't guarantee "not debited" for FAILED, so don't claim it (guide §13).
  FAILED: "This transfer failed. Check your balance and contact support with the confirmation number below.",
  CANCELLED: "This transfer was cancelled before it was posted.",
};

export function TransferDetailPage() {
  const { transferId } = useParams<{ transferId: string }>();
  const transferQuery = useTransfer(transferId);
  const cancelTransfer = useCancelTransfer(transferId ?? "");
  const [confirmingCancel, setConfirmingCancel] = useState(false);

  if (transferQuery.isLoading) {
    return <Skeleton height="260px" />;
  }
  if (transferQuery.isError) {
    return <ErrorPanel error={transferQuery.error} onRetry={() => transferQuery.refetch()} />;
  }
  const transfer = transferQuery.data;
  if (!transfer) return null;

  const terminal = isTerminalStatus(transfer.status);

  return (
    <div className="stack" style={{ maxWidth: 560, gap: "var(--space-3)" }}>
      <h1 className="page-title">Transfer {terminal ? "result" : "status"}</h1>

      <div className="card stack" aria-live="polite">
        <div className="row" style={{ justifyContent: "space-between" }}>
          <StatusBadge status={transfer.status} />
          {!terminal && <span className="metadata">Updating…</span>}
        </div>

        <span className="balance-figure">
          <CurrencyDisplay amount={transfer.amount} currency={transfer.currency} />
        </span>

        <p>{STATUS_EXPLANATION[transfer.status]}</p>

        <dl className="stack stack--tight">
          <Row label="From" value={<MaskedAccountNumber accountId={transfer.sourceAccountId} />} />
          <Row label="To" value={<MaskedAccountNumber accountId={transfer.destinationAccountId} />} />
          {/* TransferDto has no memo/reference field (the backend never echoes it back); this is the transfer's own id. */}
          <Row label="Confirmation number" value={transfer.transferId} />
          <Row label="Submitted" value={new Date(transfer.createdAt).toLocaleString()} />
          <Row label={terminal ? "Finalised" : "Last updated"} value={new Date(transfer.updatedAt).toLocaleString()} />
        </dl>

        {cancelTransfer.isError && <ErrorPanel error={cancelTransfer.error} />}
        {isCancellableStatus(transfer.status) && !confirmingCancel && (
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => {
              cancelTransfer.reset();
              setConfirmingCancel(true);
            }}
            style={{ alignSelf: "flex-start" }}
          >
            Cancel transfer
          </button>
        )}
        {isCancellableStatus(transfer.status) && confirmingCancel && (
          <div className="alert alert--warning stack stack--tight" role="alertdialog" aria-labelledby="cancel-confirm-title">
            <strong id="cancel-confirm-title">Cancel this transfer?</strong>
            <span>
              <CurrencyDisplay amount={transfer.amount} currency={transfer.currency} /> to{" "}
              <MaskedAccountNumber accountId={transfer.destinationAccountId} /> won't be sent. This can't be undone.
            </span>
            <div className="row">
              <button
                type="button"
                className="btn btn--danger"
                disabled={cancelTransfer.isPending}
                onClick={() => cancelTransfer.mutate(undefined, { onSettled: () => setConfirmingCancel(false) })}
              >
                {cancelTransfer.isPending ? "Cancelling…" : "Yes, cancel transfer"}
              </button>
              <button
                type="button"
                className="btn btn--secondary"
                disabled={cancelTransfer.isPending}
                onClick={() => setConfirmingCancel(false)}
              >
                Keep transfer
              </button>
            </div>
          </div>
        )}
      </div>

      <div className="row">
        <Link to="/transactions" className="btn btn--secondary">
          Back to transactions
        </Link>
        <Link to="/transfers/new" className="btn btn--secondary">
          New transfer
        </Link>
      </div>
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
