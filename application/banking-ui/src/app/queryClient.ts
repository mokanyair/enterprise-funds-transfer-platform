import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/errors";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        // Don't retry client errors (bad request, forbidden, not found, business rejections);
        // do retry transient network/server failures a couple of times.
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false, // never auto-retry a POST; retries must be explicit and reuse the idempotency key
    },
  },
});
