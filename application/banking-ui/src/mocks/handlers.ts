import { http, HttpResponse } from "msw";
import { env } from "../lib/env";
import {
  accountsForCustomer,
  findAccount,
  idempotencyStore,
  mockTransfers,
  toAccountSummary,
  toBalance,
  transfersForAccount,
} from "./data";
import type { ApiErrorBody, ApiErrorCode, CreateTransferRequest, Transfer, TransferStatus } from "../api/types";
import { isCancellableStatus, isTerminalStatus } from "../api/types";

const RISK_REVIEW_THRESHOLD = 10000; // mirrors funds.risk.review-threshold in application.yml
const base = env.apiUrl;

function errorBody(code: ApiErrorCode, message: string, correlationId: string, details?: ApiErrorBody["details"]): ApiErrorBody {
  return { code, message, correlationId, timestamp: new Date().toISOString(), details };
}

function fail(status: number, code: ApiErrorCode, message: string, correlationId: string, headers?: Record<string, string>) {
  return HttpResponse.json(errorBody(code, message, correlationId), {
    status,
    headers: { "X-Correlation-Id": correlationId, ...headers },
  });
}

function correlationIdFrom(request: Request): string {
  return request.headers.get("X-Correlation-Id") || crypto.randomUUID();
}

/** DevAuthProvider issues `dev:<subject>` tokens; this is the mock's only "identity provider". */
function subjectFrom(request: Request): string | null {
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer dev:(.+)$/.exec(header);
  return match?.[1] ?? null;
}

const timers = new Map<string, ReturnType<typeof setTimeout>[]>();

function scheduleProgression(transferId: string, steps: { status: TransferStatus; delayMs: number }[]) {
  let elapsed = 0;
  const handles: ReturnType<typeof setTimeout>[] = [];
  for (const step of steps) {
    elapsed += step.delayMs;
    handles.push(
      setTimeout(() => {
        const current = mockTransfers.get(transferId);
        if (!current || isTerminalStatus(current.status)) return; // e.g. already CANCELLED
        applyStep(current, step.status);
      }, elapsed),
    );
  }
  timers.set(transferId, handles);
}

function applyStep(transfer: Transfer, status: TransferStatus) {
  transfer.status = status;
  transfer.updatedAt = new Date().toISOString();
  if (status === "COMPLETED") {
    const source = findAccount(transfer.sourceAccountId);
    const destination = findAccount(transfer.destinationAccountId);
    const amount = Number(transfer.amount);
    if (source) {
      source.availableBalance -= amount;
      source.ledgerBalance -= amount;
    }
    if (destination) {
      destination.availableBalance += amount;
      destination.ledgerBalance += amount;
    }
  }
}

export const handlers = [
  http.get(`${base}/accounts`, ({ request }) => {
    const correlationId = correlationIdFrom(request);
    const subject = subjectFrom(request);
    if (!subject) return fail(401, "UNAUTHENTICATED", "Authentication is required.", correlationId);
    const accounts = accountsForCustomer(subject).map(toAccountSummary);
    return HttpResponse.json({ accounts }, { headers: { "X-Correlation-Id": correlationId } });
  }),

  http.get(`${base}/accounts/:accountId/balance`, ({ request, params }) => {
    const correlationId = correlationIdFrom(request);
    const subject = subjectFrom(request);
    if (!subject) return fail(401, "UNAUTHENTICATED", "Authentication is required.", correlationId);
    const account = findAccount(String(params.accountId));
    if (!account || account.customerSubject !== subject) {
      return fail(404, "NOT_FOUND", "The requested resource was not found.", correlationId);
    }
    return HttpResponse.json(toBalance(account), { headers: { "X-Correlation-Id": correlationId } });
  }),

  http.get(`${base}/accounts/:accountId/transfers`, ({ request, params }) => {
    const correlationId = correlationIdFrom(request);
    const subject = subjectFrom(request);
    if (!subject) return fail(401, "UNAUTHENTICATED", "Authentication is required.", correlationId);
    const account = findAccount(String(params.accountId));
    if (!account || account.customerSubject !== subject) {
      return fail(404, "NOT_FOUND", "The requested resource was not found.", correlationId);
    }
    const url = new URL(request.url);
    const page = Number(url.searchParams.get("page") ?? "0");
    const size = Number(url.searchParams.get("size") ?? "20");
    const status = (url.searchParams.get("status") as TransferStatus | null) ?? undefined;
    const all = transfersForAccount(account.accountId, status);
    const items = all.slice(page * size, page * size + size);
    return HttpResponse.json(
      { items, page, size, totalElements: all.length, totalPages: Math.max(1, Math.ceil(all.length / size)) },
      { headers: { "X-Correlation-Id": correlationId } },
    );
  }),

  http.post(`${base}/transfers`, async ({ request }) => {
    const correlationId = correlationIdFrom(request);
    const subject = subjectFrom(request);
    if (!subject) return fail(401, "UNAUTHENTICATED", "Authentication is required.", correlationId);

    const body = (await request.json()) as CreateTransferRequest;
    const idempotencyKey = request.headers.get("Idempotency-Key");
    const dedupeKey = idempotencyKey ? `${subject}:${idempotencyKey}` : null;
    const requestHash = JSON.stringify(body);

    if (dedupeKey) {
      const existing = idempotencyStore.get(dedupeKey);
      if (existing) {
        if (existing.requestHash !== requestHash) {
          return fail(409, "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used with a different request.", correlationId);
        }
        const transfer = mockTransfers.get(existing.transferId)!;
        return HttpResponse.json(transfer, {
          status: 201,
          headers: { "X-Correlation-Id": correlationId, "Idempotent-Replayed": "true", Location: `/api/v1/transfers/${transfer.transferId}` },
        });
      }
    }

    const source = findAccount(body.sourceAccountId);
    if (!source || source.customerSubject !== subject) {
      return fail(404, "NOT_FOUND", "The requested resource was not found.", correlationId);
    }
    const destination = findAccount(body.destinationAccountId);
    if (!destination) {
      return fail(404, "NOT_FOUND", "The requested resource was not found.", correlationId);
    }
    if (source.accountId === destination.accountId) {
      return fail(422, "SAME_ACCOUNT", "Source and destination accounts must differ.", correlationId);
    }
    if (source.status !== "ACTIVE" || destination.status !== "ACTIVE") {
      return fail(422, "ACCOUNT_INACTIVE", "One of the accounts is not active.", correlationId);
    }
    if (body.currency !== source.currency) {
      return fail(422, "CURRENCY_MISMATCH", "The currency does not match the accounts.", correlationId);
    }
    const amount = Number(body.amount);
    if (!(amount > 0)) {
      return fail(422, "AMOUNT_NOT_POSITIVE", "The amount must be greater than zero.", correlationId);
    }
    if (amount > source.availableBalance) {
      return fail(422, "INSUFFICIENT_FUNDS", "The source account has insufficient available funds.", correlationId);
    }

    const transferId = crypto.randomUUID();
    const now = new Date().toISOString();
    const transfer: Transfer = {
      transferId,
      sourceAccountId: source.accountId,
      destinationAccountId: destination.accountId,
      amount: amount.toFixed(4),
      currency: source.currency,
      status: amount > RISK_REVIEW_THRESHOLD ? "PENDING_REVIEW" : "RECEIVED",
      createdAt: now,
      updatedAt: now,
    };
    mockTransfers.set(transferId, transfer);
    if (dedupeKey) {
      idempotencyStore.set(dedupeKey, { requestHash, transferId });
    }

    if (transfer.status === "PENDING_REVIEW") {
      scheduleProgression(transferId, [
        { status: "PROCESSING", delayMs: 4000 },
        { status: "COMPLETED", delayMs: 2500 },
      ]);
    } else {
      scheduleProgression(transferId, [
        { status: "PROCESSING", delayMs: 1200 },
        { status: "COMPLETED", delayMs: 1200 },
      ]);
    }

    return HttpResponse.json(transfer, {
      status: 201,
      headers: { "X-Correlation-Id": correlationId, Location: `/api/v1/transfers/${transferId}` },
    });
  }),

  http.get(`${base}/transfers/:transferId`, ({ request, params }) => {
    const correlationId = correlationIdFrom(request);
    const subject = subjectFrom(request);
    if (!subject) return fail(401, "UNAUTHENTICATED", "Authentication is required.", correlationId);
    const transfer = mockTransfers.get(String(params.transferId));
    if (!transfer || !ownsTransfer(subject, transfer)) {
      return fail(404, "NOT_FOUND", "The requested resource was not found.", correlationId);
    }
    return HttpResponse.json(transfer, { headers: { "X-Correlation-Id": correlationId } });
  }),

  http.post(`${base}/transfers/:transferId/cancel`, ({ request, params }) => {
    const correlationId = correlationIdFrom(request);
    const subject = subjectFrom(request);
    if (!subject) return fail(401, "UNAUTHENTICATED", "Authentication is required.", correlationId);
    const transfer = mockTransfers.get(String(params.transferId));
    if (!transfer || !ownsTransfer(subject, transfer)) {
      return fail(404, "NOT_FOUND", "The requested resource was not found.", correlationId);
    }
    if (!isCancellableStatus(transfer.status)) {
      return fail(409, "TRANSFER_NOT_CANCELLABLE", "The transfer has already been posted and cannot be cancelled.", correlationId);
    }
    for (const handle of timers.get(transfer.transferId) ?? []) clearTimeout(handle);
    transfer.status = "CANCELLED";
    transfer.updatedAt = new Date().toISOString();
    return HttpResponse.json(transfer, { headers: { "X-Correlation-Id": correlationId } });
  }),
];

function ownsTransfer(subject: string, transfer: Transfer): boolean {
  const source = findAccount(transfer.sourceAccountId);
  const destination = findAccount(transfer.destinationAccountId);
  return source?.customerSubject === subject || destination?.customerSubject === subject;
}
