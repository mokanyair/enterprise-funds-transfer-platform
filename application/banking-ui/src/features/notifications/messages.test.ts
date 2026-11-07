import { describe, expect, it } from "vitest";
import type { AppNotification } from "../../api/types";
import { describeNotification } from "./messages";

function n(over: Partial<AppNotification>): AppNotification {
  return {
    notificationId: "n1",
    category: "TRANSACTION",
    type: "TRANSFER_RECEIVED",
    entityType: "TRANSFER",
    entityId: "t1",
    params: { transferId: "t1", amount: "25.00", currency: "USD", counterpartyLast4: "1001" },
    occurredAt: "2026-10-18T09:00:00.000Z",
    createdAt: "2026-10-18T09:00:00.250Z",
    readAt: null,
    ...over,
  };
}

describe("describeNotification", () => {
  it("renders a received transfer with formatted money and a masked counterparty, linking to the transfer", () => {
    const m = describeNotification(n({}));
    expect(m.title).toBe("Money received");
    expect(m.body).toMatch(/^You received .*25\.00 from •••• 1001\.$/);
    expect(m.href).toBe("/transfers/t1");
  });

  it.each([
    ["TRANSFER_SENT", "Transfer sent"],
    ["TRANSFER_UNDER_REVIEW", "Transfer under review"],
    ["TRANSFER_REJECTED", "Transfer not completed"],
    ["TRANSFER_CANCELLED", "Transfer cancelled"],
  ])("has a title for %s", (type, title) => {
    expect(describeNotification(n({ type })).title).toBe(title);
  });

  it("turns beneficiary changes into a security prompt linking to beneficiaries", () => {
    const m = describeNotification(
      n({ category: "SECURITY", type: "BENEFICIARY_ADDED", entityType: "BENEFICIARY", params: { accountIdLast4: "2001" } }),
    );
    expect(m.body).toBe("Account •••• 2001 was added to your beneficiaries. If this wasn't you, contact us right away.");
    expect(m.href).toBe("/beneficiaries");
  });

  it("degrades gracefully when params are missing", () => {
    expect(describeNotification(n({ type: "TRANSFER_REJECTED", params: {} })).body).toBe(
      "Your transfer to another account was not completed.",
    );
  });

  it("gives an unknown type a generic message instead of failing", () => {
    const m = describeNotification(n({ type: "STATEMENT_READY", entityType: "BENEFICIARY" }));
    expect(m).toEqual({ title: "Account update", body: "You have a new notification.", href: null });
  });
});
