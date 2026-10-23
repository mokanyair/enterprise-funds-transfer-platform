import type { AppNotification } from "../../api/types";
import { formatMoney, maskAccountId } from "../../lib/format";

export interface NotificationMessage {
  title: string;
  body: string;
  /** Where "View" goes; null when there is nothing to open. */
  href: string | null;
}

const NOT_YOU = "If this wasn't you, contact us right away.";

/**
 * Wording lives here, not in the API (N2): the server sends a type and structured params only, never free text or a
 * full account number. A missing param degrades the sentence instead of failing, and an unknown type gets a generic
 * message so a newer backend never breaks an older client.
 */
export function describeNotification(n: AppNotification): NotificationMessage {
  const p = n.params;
  const money = p.amount && p.currency ? formatMoney(p.amount, p.currency) : null;
  const counterparty = p.counterpartyLast4 ? maskAccountId(p.counterpartyLast4) : "another account";
  const yourTransfer = money ? `Your ${money} transfer` : "Your transfer";
  const transferHref = n.entityType === "TRANSFER" ? `/transfers/${encodeURIComponent(n.entityId)}` : null;
  const account = p.accountIdLast4 ? `Account ${maskAccountId(p.accountIdLast4)}` : "An account";

  switch (n.type) {
    case "TRANSFER_SENT":
      return { title: "Transfer sent", body: `You sent ${money ?? "money"} to ${counterparty}.`, href: transferHref };
    case "TRANSFER_RECEIVED":
      return { title: "Money received", body: `You received ${money ?? "money"} from ${counterparty}.`, href: transferHref };
    case "TRANSFER_UNDER_REVIEW":
      return {
        title: "Transfer under review",
        body: `${yourTransfer} to ${counterparty} is being reviewed. We'll let you know the outcome.`,
        href: transferHref,
      };
    case "TRANSFER_REJECTED":
      return { title: "Transfer not completed", body: `${yourTransfer} to ${counterparty} was not completed.`, href: transferHref };
    case "TRANSFER_CANCELLED":
      return { title: "Transfer cancelled", body: `${yourTransfer} to ${counterparty} was cancelled.`, href: transferHref };
    case "BENEFICIARY_ADDED":
      return { title: "New beneficiary saved", body: `${account} was added to your beneficiaries. ${NOT_YOU}`, href: "/beneficiaries" };
    case "BENEFICIARY_DELETED":
      return { title: "Beneficiary removed", body: `${account} was removed from your beneficiaries. ${NOT_YOU}`, href: "/beneficiaries" };
    default:
      return { title: "Account update", body: "You have a new notification.", href: transferHref };
  }
}
