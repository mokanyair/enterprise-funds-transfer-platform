/**
 * Mirrors funds-transfer-service DTOs exactly (see web/dto/*.java and
 * src/main/resources/openapi/**). "accountId" fields are the public account
 * number (e.g. "ACC001"), never the internal database UUID.
 */

export type AccountStatus = "ACTIVE" | "FROZEN" | "CLOSED";

export interface AccountSummary {
  accountId: string;
  currency: string;
  status: AccountStatus;
  availableBalance: string;
  ledgerBalance: string;
}

export interface AccountList {
  accounts: AccountSummary[];
}

export interface Balance {
  accountId: string;
  currency: string;
  availableBalance: string;
  ledgerBalance: string;
  asOf: string;
}

export const TRANSFER_STATUSES = [
  "RECEIVED",
  "VALIDATING",
  "PENDING_REVIEW",
  "PROCESSING",
  "COMPLETED",
  "REJECTED",
  "FAILED",
  "CANCELLED",
] as const;

export type TransferStatus = (typeof TRANSFER_STATUSES)[number];

const TERMINAL_STATUSES: ReadonlySet<TransferStatus> = new Set(["COMPLETED", "REJECTED", "FAILED", "CANCELLED"]);
const CANCELLABLE_STATUSES: ReadonlySet<TransferStatus> = new Set(["RECEIVED", "VALIDATING", "PENDING_REVIEW"]);

export function isTerminalStatus(status: TransferStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}

export function isCancellableStatus(status: TransferStatus): boolean {
  return CANCELLABLE_STATUSES.has(status);
}

export interface Transfer {
  transferId: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  currency: string;
  status: TransferStatus;
  createdAt: string;
  updatedAt: string;
}

export interface TransferPage {
  items: Transfer[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CreateTransferRequest {
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  currency: string;
  reference?: string;
}

export type ApiErrorCode =
  | "INVALID_REQUEST"
  | "UNAUTHENTICATED"
  | "FORBIDDEN"
  | "NOT_FOUND"
  | "IDEMPOTENCY_KEY_REUSED"
  | "IDEMPOTENCY_IN_PROGRESS"
  | "IDEMPOTENCY_KEY_EXPIRED"
  | "TRANSFER_NOT_CANCELLABLE"
  | "INSUFFICIENT_FUNDS"
  | "ACCOUNT_INACTIVE"
  | "CURRENCY_MISMATCH"
  | "SAME_ACCOUNT"
  | "AMOUNT_NOT_POSITIVE"
  | "LIMIT_EXCEEDED"
  | "TRANSFER_REJECTED"
  | "INTERNAL_ERROR";

export interface ApiErrorBody {
  code: ApiErrorCode;
  message: string;
  correlationId: string;
  timestamp: string;
  details?: { field: string; issue: string }[];
}
