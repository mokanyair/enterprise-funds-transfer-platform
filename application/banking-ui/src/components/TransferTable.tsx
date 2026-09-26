import { useNavigate } from "react-router-dom";
import type { Transfer } from "../api/types";
import { StatusBadge } from "./StatusBadge";
import { CurrencyDisplay } from "./CurrencyDisplay";
import { MaskedAccountNumber } from "./MaskedAccountNumber";
import { EmptyState } from "./EmptyState";

export function TransferTable({ transfers, perspectiveAccountId }: { transfers: Transfer[]; perspectiveAccountId?: string }) {
  const navigate = useNavigate();

  if (transfers.length === 0) {
    return <EmptyState title="No transactions yet" description="Transfers will appear here once they're submitted." />;
  }

  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Direction</th>
            <th scope="col">Amount</th>
            <th scope="col">Status</th>
            <th scope="col">Confirmation #</th>
          </tr>
        </thead>
        <tbody>
          {transfers.map((t) => {
            const direction = perspectiveAccountId
              ? t.sourceAccountId === perspectiveAccountId
                ? "Sent"
                : "Received"
              : undefined;
            return (
              <tr
                key={t.transferId}
                tabIndex={0}
                role="button"
                onClick={() => navigate(`/transfers/${t.transferId}`)}
                onKeyDown={(e) => e.key === "Enter" && navigate(`/transfers/${t.transferId}`)}
              >
                <td>{new Date(t.createdAt).toLocaleString()}</td>
                <td>
                  {direction ? (
                    <span>
                      {direction} · <MaskedAccountNumber accountId={direction === "Sent" ? t.destinationAccountId : t.sourceAccountId} />
                    </span>
                  ) : (
                    <span>
                      <MaskedAccountNumber accountId={t.sourceAccountId} /> → <MaskedAccountNumber accountId={t.destinationAccountId} />
                    </span>
                  )}
                </td>
                <td>
                  <CurrencyDisplay amount={t.amount} currency={t.currency} />
                </td>
                <td>
                  <StatusBadge status={t.status} />
                </td>
                <td>{t.transferId.slice(0, 8)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
