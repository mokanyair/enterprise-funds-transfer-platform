import type { CreateTransferRequest } from "../../api/types";

/**
 * A submitted transfer whose outcome the browser hasn't seen yet. Written *before* the POST
 * and cleared only once the server's answer is known, so a timeout, reload, closed tab or
 * sign-in redirect can't turn one logical transfer into two: resuming resends the identical
 * body under the identical Idempotency-Key and the backend replays its original result.
 */
export interface PendingTransfer {
  idempotencyKey: string;
  body: CreateTransferRequest;
  /** Owner of the attempt; another user signing in on this tab must not resume it. */
  subject: string;
  submittedAt: string;
}

const STORAGE_KEY = "novabank.pending-transfer";

export function loadPendingTransfer(subject: string | undefined): PendingTransfer | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const pending = JSON.parse(raw) as PendingTransfer;
    return subject && pending.subject === subject ? pending : null;
  } catch {
    return null;
  }
}

export function savePendingTransfer(pending: PendingTransfer): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending));
  } catch {
    // Storage unavailable (private mode quota etc.): in-memory state still guards this page.
  }
}

export function clearPendingTransfer(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}
