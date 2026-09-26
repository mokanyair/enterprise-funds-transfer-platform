import type { TransferStatus, AccountStatus } from "../api/types";

const TRANSFER_VARIANT: Record<TransferStatus, string> = {
  RECEIVED: "info",
  VALIDATING: "info",
  PENDING_REVIEW: "warning",
  PROCESSING: "warning",
  COMPLETED: "success",
  REJECTED: "error",
  FAILED: "error",
  CANCELLED: "neutral",
};

const ACCOUNT_VARIANT: Record<AccountStatus, string> = {
  ACTIVE: "success",
  FROZEN: "warning",
  CLOSED: "neutral",
};

const LABELS: Record<string, string> = {
  RECEIVED: "Received",
  VALIDATING: "Validating",
  PENDING_REVIEW: "Under review",
  PROCESSING: "Processing",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
  ACTIVE: "Active",
  FROZEN: "Frozen",
  CLOSED: "Closed",
};

/** Color is always paired with a text label (never the only signal), per the accessibility rule. */
export function StatusBadge({ status }: { status: TransferStatus | AccountStatus }) {
  const variant = TRANSFER_VARIANT[status as TransferStatus] ?? ACCOUNT_VARIANT[status as AccountStatus] ?? "neutral";
  return <span className={`status-badge status-badge--${variant}`}>{LABELS[status] ?? status}</span>;
}
