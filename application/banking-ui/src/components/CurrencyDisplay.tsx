import { formatMoney } from "../lib/format";

export function CurrencyDisplay({
  amount,
  currency,
  className,
}: {
  amount: string;
  currency: string;
  className?: string;
}) {
  return <span className={className}>{formatMoney(amount, currency)}</span>;
}
