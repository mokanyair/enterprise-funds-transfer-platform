import { useMutation, useQueryClient } from "@tanstack/react-query";
import { transfersApi } from "../api/transfers";
import type { CreateTransferRequest } from "../api/types";

export function useCreateTransfer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: CreateTransferRequest; idempotencyKey: string }) =>
      transfersApi.create(body, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
      queryClient.invalidateQueries({ queryKey: ["account"] });
    },
  });
}
