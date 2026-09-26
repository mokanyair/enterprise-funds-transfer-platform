import { maskAccountId } from "../lib/format";

export function MaskedAccountNumber({ accountId }: { accountId: string }) {
  return <span aria-label={`Account ending in ${accountId.slice(-4)}`}>{maskAccountId(accountId)}</span>;
}
