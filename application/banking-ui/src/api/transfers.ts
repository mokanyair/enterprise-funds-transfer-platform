import { http } from "./http";
import type { CreateTransferRequest, Transfer } from "./types";

export const transfersApi = {
  /** Idempotency-Key must stay the same across retries of the same logical attempt. */
  async create(body: CreateTransferRequest, idempotencyKey: string): Promise<Transfer> {
    const { data } = await http.post<Transfer>("/transfers", body, {
      headers: { "Idempotency-Key": idempotencyKey },
    });
    return data;
  },

  async get(transferId: string): Promise<Transfer> {
    const { data } = await http.get<Transfer>(`/transfers/${encodeURIComponent(transferId)}`);
    return data;
  },

  async cancel(transferId: string): Promise<Transfer> {
    const { data } = await http.post<Transfer>(`/transfers/${encodeURIComponent(transferId)}/cancel`);
    return data;
  },
};
