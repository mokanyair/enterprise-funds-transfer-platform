import type { AccountStatus, AppNotification, NotificationCategory, NotificationParams, Transfer, TransferStatus } from "../api/types";

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

function seedTransfer(source: string, destination: string, amount: number, status: TransferStatus): string {
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
  return id;
}

seedTransfer("ACC1001", "ACC1002", 250, "COMPLETED");
const seededReceived = seedTransfer("ACC2001", "ACC1001", 60, "COMPLETED");
seedTransfer("ACC1002", "ACC1001", 1200, "COMPLETED");
seedTransfer("ACC1001", "ACC2001", 45.5, "FAILED");
const seededReview = seedTransfer("ACC1001", "ACC2002", 15000, "PENDING_REVIEW");

export function transfersForAccount(accountId: string, status?: TransferStatus): Transfer[] {
  return [...mockTransfers.values()]
    .filter((t) => t.sourceAccountId === accountId || t.destinationAccountId === accountId)
    .filter((t) => !status || t.status === status)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export interface MockBeneficiary {
  beneficiaryId: string;
  customerSubject: string;
  accountId: string;
  nickname: string;
  status: "ACTIVE" | "DELETED";
  createdAt: string;
}

/** Mirrors funds.beneficiaries.* defaults in application.yml (decision B5). */
export const BENEFICIARY_MAX_ACTIVE = 50;
export const BENEFICIARY_MAX_ADDS_PER_DAY = 20;

export const mockBeneficiaries: MockBeneficiary[] = [
  {
    beneficiaryId: crypto.randomUUID(),
    customerSubject: "customer-1",
    accountId: "ACC2001",
    nickname: "Sam - shared rent",
    status: "ACTIVE",
    createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  },
];

export function activeBeneficiaries(subject: string): MockBeneficiary[] {
  return mockBeneficiaries
    .filter((b) => b.customerSubject === subject && b.status === "ACTIVE")
    .sort((a, b) => a.nickname.toLowerCase().localeCompare(b.nickname.toLowerCase()) || a.beneficiaryId.localeCompare(b.beneficiaryId));
}

export function toBeneficiary(b: MockBeneficiary) {
  return { beneficiaryId: b.beneficiaryId, accountId: b.accountId, nickname: b.nickname, createdAt: b.createdAt };
}

const seededBeneficiaries = mockBeneficiaries.map((b) => ({ ...b }));

/** Tests mutate the shared mock store; this puts the seed back. */
export function resetMockBeneficiaries(): void {
  mockBeneficiaries.splice(0, mockBeneficiaries.length, ...seededBeneficiaries.map((b) => ({ ...b })));
}

// ---- Release 2: notifications. Mirrors NotificationProjector: who is told what, and params without free text.

export interface MockNotification extends AppNotification {
  customerSubject: string;
}

export const mockNotifications: MockNotification[] = [];

function last4(value: string): string {
  return value.slice(-4);
}

export function pushNotification(
  customerSubject: string,
  category: NotificationCategory,
  type: string,
  entity: { type: "TRANSFER" | "BENEFICIARY"; id: string },
  params: NotificationParams,
  at: string = new Date().toISOString(),
): void {
  mockNotifications.push({
    notificationId: crypto.randomUUID(),
    customerSubject,
    category,
    type,
    entityType: entity.type,
    entityId: entity.id,
    params,
    occurredAt: at,
    createdAt: at,
    readAt: null,
  });
}

/** Same mapping as the consumer: COMPLETED tells both sides, the others tell the sender only. */
export function notifyTransfer(transfer: Transfer, status: TransferStatus): void {
  const source = findAccount(transfer.sourceAccountId);
  const destination = findAccount(transfer.destinationAccountId);
  if (!source) return;
  const base = { transferId: transfer.transferId, amount: transfer.amount, currency: transfer.currency };
  const entity = { type: "TRANSFER" as const, id: transfer.transferId };
  const toSource = (type: string) =>
    pushNotification(source.customerSubject, "TRANSACTION", type, entity, { ...base, counterpartyLast4: last4(transfer.destinationAccountId) });
  switch (status) {
    case "COMPLETED":
      toSource("TRANSFER_SENT");
      if (destination && destination.customerSubject !== source.customerSubject) {
        pushNotification(destination.customerSubject, "TRANSACTION", "TRANSFER_RECEIVED", entity, {
          ...base,
          counterpartyLast4: last4(transfer.sourceAccountId),
        });
      }
      break;
    case "PENDING_REVIEW":
      toSource("TRANSFER_UNDER_REVIEW");
      break;
    case "REJECTED":
      toSource("TRANSFER_REJECTED");
      break;
    case "CANCELLED":
      toSource("TRANSFER_CANCELLED");
      break;
    default:
      break;
  }
}

export function notifyBeneficiary(b: MockBeneficiary, type: "BENEFICIARY_ADDED" | "BENEFICIARY_DELETED"): void {
  pushNotification(b.customerSubject, "SECURITY", type, { type: "BENEFICIARY", id: b.beneficiaryId }, {
    beneficiaryId: b.beneficiaryId,
    accountIdLast4: last4(b.accountId),
  });
}

function hoursAgo(h: number): string {
  return new Date(Date.now() - h * 3_600_000).toISOString();
}

{
  const received = mockTransfers.get(seededReceived)!;
  const review = mockTransfers.get(seededReview)!;
  const sam = mockBeneficiaries[0];
  pushNotification("customer-1", "SECURITY", "BENEFICIARY_ADDED", { type: "BENEFICIARY", id: sam.beneficiaryId },
    { beneficiaryId: sam.beneficiaryId, accountIdLast4: last4(sam.accountId) }, sam.createdAt);
  mockNotifications[mockNotifications.length - 1].readAt = hoursAgo(70);
  pushNotification("customer-1", "TRANSACTION", "TRANSFER_RECEIVED", { type: "TRANSFER", id: received.transferId },
    { transferId: received.transferId, amount: received.amount, currency: "USD", counterpartyLast4: "2001" }, hoursAgo(5));
  pushNotification("customer-1", "TRANSACTION", "TRANSFER_UNDER_REVIEW", { type: "TRANSFER", id: review.transferId },
    { transferId: review.transferId, amount: review.amount, currency: "USD", counterpartyLast4: "2002" }, hoursAgo(1));
}

/** Newest first, notificationId as tiebreak (N3). */
export function notificationsFor(subject: string, category?: string | null, unreadOnly = false): MockNotification[] {
  return mockNotifications
    .filter((n) => n.customerSubject === subject && (!category || n.category === category) && (!unreadOnly || n.readAt === null))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.notificationId.localeCompare(a.notificationId));
}

export function toNotification(n: MockNotification): AppNotification {
  const { customerSubject: _owner, ...wire } = n;
  return { ...wire, params: { ...n.params } };
}

const seededNotifications = mockNotifications.map((n) => ({ ...n }));

export function resetMockNotifications(): void {
  mockNotifications.splice(0, mockNotifications.length, ...seededNotifications.map((n) => ({ ...n })));
}
