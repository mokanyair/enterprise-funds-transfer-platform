import type { ApiErrorBody, ApiErrorCode } from "./types";

/** Normalized shape every API caller deals with, whether the backend, the network, or MSW failed. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode | "NETWORK_ERROR";
  readonly correlationId?: string;
  readonly details?: { field: string; issue: string }[];
  /** Seconds, from a Retry-After header (e.g. IDEMPOTENCY_IN_PROGRESS). */
  readonly retryAfterSeconds?: number;
  /** From a Location header (e.g. IDEMPOTENCY_KEY_EXPIRED points at the original transfer). */
  readonly location?: string;

  constructor(status: number, body: ApiErrorBody | null, headers: { retryAfter?: string; location?: string } = {}) {
    super(body?.message ?? defaultMessageFor(status));
    this.status = status;
    this.code = body?.code ?? "NETWORK_ERROR";
    this.correlationId = body?.correlationId;
    this.details = body?.details;
    const retryAfter = Number(headers.retryAfter);
    this.retryAfterSeconds = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined;
    this.location = headers.location || undefined;
  }
}

function defaultMessageFor(status: number): string {
  if (status === 0) {
    return "Could not reach the server. Check your connection and try again.";
  }
  return "Something went wrong. Please try again.";
}

/** Safe, user-facing text. Never surfaces backend internals (the backend already guarantees that). */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return "Something went wrong. Please try again.";
}

/**
 * True when we can't know whether the server applied the request (network drop, timeout, 5xx,
 * or the same key still in flight). Only these may be resubmitted — with the same Idempotency-Key.
 */
export function isOutcomeUnknown(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false;
  return error.status === 0 || error.status >= 500 || error.code === "IDEMPOTENCY_IN_PROGRESS";
}
