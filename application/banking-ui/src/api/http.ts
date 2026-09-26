import axios from "axios";
import { env } from "../lib/env";
import { ApiError } from "./errors";
import type { ApiErrorBody } from "./types";

let accessTokenGetter: () => string | null = () => null;
let onUnauthenticated: () => void = () => {};

/** Wired by the active AuthProvider on mount; keeps the API client decoupled from auth internals. */
export function setAccessTokenGetter(fn: () => string | null): void {
  accessTokenGetter = fn;
}

export function setUnauthenticatedHandler(fn: () => void): void {
  onUnauthenticated = fn;
}

export const http = axios.create({
  baseURL: env.apiUrl,
  timeout: 15000,
});

http.interceptors.request.use((config) => {
  const token = accessTokenGetter();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  config.headers.set("X-Correlation-Id", crypto.randomUUID());
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (!error.response) {
      return Promise.reject(new ApiError(0, null));
    }
    const body = (error.response.data ?? null) as ApiErrorBody | null;
    const apiError = new ApiError(error.response.status, body);
    if (apiError.status === 401) {
      onUnauthenticated();
    }
    return Promise.reject(apiError);
  },
);
