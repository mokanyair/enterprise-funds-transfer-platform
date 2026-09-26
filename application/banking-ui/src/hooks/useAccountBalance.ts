import { useQuery } from "@tanstack/react-query";
import { accountsApi } from "../api/accounts";

export function useAccountBalance(accountId: string | undefined) {
  return useQuery({
    queryKey: ["account", accountId, "balance"],
    queryFn: () => accountsApi.balance(accountId!),
    enabled: Boolean(accountId),
  });
}
