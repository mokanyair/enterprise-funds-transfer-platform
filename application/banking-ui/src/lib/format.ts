/** Last four characters only; the full account number is never rendered by default (guide §31). */
export function maskAccountId(accountId: string): string {
  return `•••• ${accountId.slice(-4)}`;
}

/**
 * Display-only formatting of a backend decimal string. The value is never fed back into
 * arithmetic — the backend is the only source of truth for money math (guide §24).
 */
export function formatMoney(amount: string, currency: string): string {
  const value = Number(amount);
  if (!Number.isFinite(value)) {
    return `${amount} ${currency}`;
  }
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(value);
  } catch {
    return `${amount} ${currency}`; // unknown ISO code
  }
}
