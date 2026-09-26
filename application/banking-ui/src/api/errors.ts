import type { ApiErrorBody, ApiErrorCode } from "./types";

/** Normalized shape every API caller deals with, whether the backend, the network, or MSW failed. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ApiErrorCode | "NETWORK_ERROR";
  readonly correlationId?: string;
  readonly details?: { field: string; issue: string }[];

  constructor(status: number, body: ApiErrorBody | null) {
    super(body?.message ?? defaultMessageFor(status));
    this.status = status;
    this.code = body?.code ?? "NETWORK_ERROR";
    this.correlationId = body?.correlationId;
    this.details = body?.details;
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
