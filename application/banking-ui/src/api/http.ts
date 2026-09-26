import axios, { type AxiosError, type InternalAxiosRequestConfig } from "axios";
import { env } from "../lib/env";
import { ApiError } from "./errors";
import type { ApiErrorBody } from "./types";

/** What the active AuthProvider plugs in; keeps the API client decoupled from auth internals. */
export interface TokenSource {
  /** A token valid for at least the next few seconds, refreshing first if needed; null if signed out. */
  getToken(): Promise<string | null>;
  /** Refresh regardless of expiry (after a 401). Resolves false if the session can't be renewed. */
  forceRefresh(): Promise<boolean>;
  /** Session is gone: send the user back through sign-in. */
  onUnauthenticated(): void;
}

const signedOut: TokenSource = {
  getToken: async () => null,
  forceRefresh: async () => false,
  onUnauthenticated: () => {},
};

let tokenSource: TokenSource = signedOut;

export function setTokenSource(source: TokenSource | null): void {
  tokenSource = source ?? signedOut;
}

type RetriableConfig = InternalAxiosRequestConfig & { _authRetried?: boolean };

export const http = axios.create({
  baseURL: env.apiUrl,
  timeout: 15000,
});

http.interceptors.request.use(async (config) => {
  const token = await tokenSource.getToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  if (!config.headers.has("X-Correlation-Id")) {
    config.headers.set("X-Correlation-Id", crypto.randomUUID());
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (!error.response) {
      return Promise.reject(new ApiError(0, null));
    }
    const config = error.config as RetriableConfig | undefined;

    // A 401 usually means the token expired in flight. Refresh once and replay the same request —
    // safe even for POST /transfers because the Idempotency-Key header is replayed with it.
    if (error.response.status === 401 && config && !config._authRetried) {
      config._authRetried = true;
      if (await tokenSource.forceRefresh()) {
        return http.request(config);
      }
    }

    const body = (error.response.data ?? null) as ApiErrorBody | null;
    const apiError = new ApiError(error.response.status, body, {
      retryAfter: error.response.headers["retry-after"],
      location: error.response.headers["location"],
    });
    if (apiError.status === 401) {
      tokenSource.onUnauthenticated();
    }
    return Promise.reject(apiError);
  },
);
