import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { transfersApi } from "../api/transfers";
import { isTerminalStatus } from "../api/types";

/** Polls while the backend-reported status is non-terminal; the UI never infers completion itself. */
export function useTransfer(transferId: string | undefined) {
  return useQuery({
    queryKey: ["transfer", transferId],
    queryFn: () => transfersApi.get(transferId!),
    enabled: Boolean(transferId),
    refetchInterval: (query) => (query.state.data && isTerminalStatus(query.state.data.status) ? false : 2500),
  });
}

export function useCancelTransfer(transferId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => transfersApi.cancel(transferId),
    onSuccess: (transfer) => {
      queryClient.setQueryData(["transfer", transferId], transfer);
      // Balances and history lists showed this transfer as pending; refetch them.
      queryClient.invalidateQueries({ queryKey: ["accounts"] });
      queryClient.invalidateQueries({ queryKey: ["account"] });
    },
    onError: () => {
      // e.g. TRANSFER_NOT_CANCELLABLE: the status moved on server-side; show the real one.
      queryClient.invalidateQueries({ queryKey: ["transfer", transferId] });
    },
  });
}
