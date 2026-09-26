import { useQuery } from "@tanstack/react-query";
import { accountsApi } from "../api/accounts";
import type { TransferStatus } from "../api/types";

export function useAccountHistory(
  accountId: string | undefined,
  params: { page: number; size: number; status?: TransferStatus },
) {
  return useQuery({
    queryKey: ["account", accountId, "transfers", params],
    queryFn: () => accountsApi.history(accountId!, params),
    enabled: Boolean(accountId),
    placeholderData: (previous) => previous,
  });
}
