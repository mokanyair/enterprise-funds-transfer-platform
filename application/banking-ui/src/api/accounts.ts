import { http } from "./http";
import type { AccountList, Balance, TransferPage, TransferStatus } from "./types";

export const accountsApi = {
  async list(): Promise<AccountList> {
    const { data } = await http.get<AccountList>("/accounts");
    return data;
  },

  async balance(accountId: string): Promise<Balance> {
    const { data } = await http.get<Balance>(`/accounts/${encodeURIComponent(accountId)}/balance`);
    return data;
  },

  async history(
    accountId: string,
    params: { page?: number; size?: number; status?: TransferStatus },
  ): Promise<TransferPage> {
    const { data } = await http.get<TransferPage>(`/accounts/${encodeURIComponent(accountId)}/transfers`, {
      params,
    });
    return data;
  },
};
