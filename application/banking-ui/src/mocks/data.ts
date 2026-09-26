import type { AccountStatus, Transfer, TransferStatus } from "../api/types";

export interface MockAccount {
  accountId: string;
  customerSubject: string;
  currency: string;
  status: AccountStatus;
  availableBalance: number;
  ledgerBalance: number;
}

/** Matches the two demo customers offered on the dev login screen. */
export const mockAccounts: MockAccount[] = [
  { accountId: "ACC1001", customerSubject: "customer-1", currency: "USD", status: "ACTIVE", availableBalance: 2450.75, ledgerBalance: 2450.75 },
  { accountId: "ACC1002", customerSubject: "customer-1", currency: "USD", status: "ACTIVE", availableBalance: 15320.1, ledgerBalance: 15320.1 },
  { accountId: "ACC1003", customerSubject: "customer-1", currency: "USD", status: "FROZEN", availableBalance: 500, ledgerBalance: 500 },
  { accountId: "ACC2001", customerSubject: "customer-2", currency: "USD", status: "ACTIVE", availableBalance: 875.25, ledgerBalance: 875.25 },
  { accountId: "ACC2002", customerSubject: "customer-2", currency: "USD", status: "ACTIVE", availableBalance: 4200, ledgerBalance: 4200 },
];

export const mockTransfers = new Map<string, Transfer>();

interface IdempotencyRecord {
  requestHash: string;
  transferId: string;
}

export const idempotencyStore = new Map<string, IdempotencyRecord>();

function formatMoney(value: number): string {
  return value.toFixed(4);
}

export function findAccount(accountId: string): MockAccount | undefined {
  return mockAccounts.find((a) => a.accountId === accountId);
}

export function accountsForCustomer(subject: string): MockAccount[] {
  return mockAccounts.filter((a) => a.customerSubject === subject).sort((a, b) => a.accountId.localeCompare(b.accountId));
}

export function toAccountSummary(account: MockAccount) {
  return {
    accountId: account.accountId,
    currency: account.currency,
    status: account.status,
    availableBalance: formatMoney(account.availableBalance),
    ledgerBalance: formatMoney(account.ledgerBalance),
  };
}

export function toBalance(account: MockAccount) {
  return {
    accountId: account.accountId,
    currency: account.currency,
    availableBalance: formatMoney(account.availableBalance),
    ledgerBalance: formatMoney(account.ledgerBalance),
    asOf: new Date().toISOString(),
  };
}

let seq = 0;
function nextTimestamp(): string {
  seq += 1;
  return new Date(Date.now() - (50 - seq) * 60_000).toISOString();
}

function seedTransfer(source: string, destination: string, amount: number, status: TransferStatus) {
  const id = crypto.randomUUID();
  const createdAt = nextTimestamp();
  mockTransfers.set(id, {
    transferId: id,
    sourceAccountId: source,
    destinationAccountId: destination,
    amount: formatMoney(amount),
    currency: "USD",
    status,
    createdAt,
    updatedAt: createdAt,
  });
}

seedTransfer("ACC1001", "ACC1002", 250, "COMPLETED");
seedTransfer("ACC2001", "ACC1001", 60, "COMPLETED");
seedTransfer("ACC1002", "ACC1001", 1200, "COMPLETED");
seedTransfer("ACC1001", "ACC2001", 45.5, "FAILED");
seedTransfer("ACC1001", "ACC2002", 15000, "PENDING_REVIEW");

export function transfersForAccount(accountId: string, status?: TransferStatus): Transfer[] {
  return [...mockTransfers.values()]
    .filter((t) => t.sourceAccountId === accountId || t.destinationAccountId === accountId)
    .filter((t) => !status || t.status === status)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}
