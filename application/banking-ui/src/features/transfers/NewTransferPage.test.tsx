import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, delay } from "msw";
import { server } from "../../test/server";
import { CUSTOMER_1, renderRoute } from "../../test/render";
import { loadPendingTransfer, savePendingTransfer } from "./pendingTransfer";
import { NewTransferPage } from "./NewTransferPage";

const API = "http://api.test/api/v1";

async function fillAndReview(user: ReturnType<typeof userEvent.setup>, amount = "25.50") {
  await screen.findByLabelText("From account");
  await user.type(screen.getByLabelText("To account number"), "ACC1002");
  await user.type(screen.getByLabelText(/^Amount/), amount);
  await user.click(screen.getByRole("button", { name: "Review transfer" }));
  await screen.findByRole("heading", { name: "Review and confirm" });
}

describe("NewTransferPage", () => {
  it("masks account numbers in the source selector", async () => {
    renderRoute(<NewTransferPage />);
    const select = await screen.findByLabelText("From account");
    expect(select).toHaveTextContent("•••• 1001");
    expect(select).not.toHaveTextContent("ACC1001");
  });

  it("shows validation errors without calling the backend", async () => {
    const user = userEvent.setup();
    let posted = false;
    server.use(http.post(`${API}/transfers`, () => { posted = true; return HttpResponse.json({}); }));
    renderRoute(<NewTransferPage />);
    await screen.findByLabelText("From account");
    await user.click(screen.getByRole("button", { name: "Review transfer" }));
    expect(await screen.findByText("Enter a destination account number.")).toBeInTheDocument();
    expect(posted).toBe(false);
  });

  it("reviews, then submits once with an Idempotency-Key and opens the result", async () => {
    const user = userEvent.setup();
    const keys: (string | null)[] = [];
    server.events.on("request:start", ({ request }) => {
      if (request.method === "POST") keys.push(request.headers.get("Idempotency-Key"));
    });
    renderRoute(<NewTransferPage />, { path: "/transfers/new" });
    await fillAndReview(user);

    await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
    expect(await screen.findByText(/Transfer detail page/)).toBeInTheDocument();
    expect(keys).toHaveLength(1);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    server.events.removeAllListeners();
  });

  it("disables Confirm while the request is in flight", async () => {
    const user = userEvent.setup();
    server.use(http.post(`${API}/transfers`, async () => { await delay("infinite"); return HttpResponse.json({}); }));
    renderRoute(<NewTransferPage />);
    await fillAndReview(user);

    await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
    expect(await screen.findByRole("button", { name: "Submitting…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Edit" })).toBeDisabled();
  });

  it("retries a failed submit with the same Idempotency-Key", async () => {
    const user = userEvent.setup();
    const keys: (string | null)[] = [];
    server.use(
      http.post(`${API}/transfers`, ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        return HttpResponse.json(
          { code: "INTERNAL_ERROR", message: "Something went wrong.", correlationId: "c-1", timestamp: "" },
          { status: 500 },
        );
      }, { once: true }),
      http.post(`${API}/transfers`, ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        return HttpResponse.json({
          transferId: "t-retry", sourceAccountId: "ACC1001", destinationAccountId: "ACC1002", amount: "25.5000",
          currency: "USD", status: "RECEIVED", createdAt: "", updatedAt: "",
        }, { status: 201 });
      }),
    );
    renderRoute(<NewTransferPage />);
    await fillAndReview(user);

    await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
    await user.click(await screen.findByRole("button", { name: "Retry same transfer" }));
    await screen.findByText(/Transfer detail page/);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it("shows a business rejection without offering a blind retry", async () => {
    const user = userEvent.setup();
    renderRoute(<NewTransferPage />);
    await fillAndReview(user, "999999");

    await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
    expect(await screen.findByText(/insufficient available funds/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry same transfer" })).not.toBeInTheDocument();
  });

  it("starts a new idempotency key after the customer edits the transfer", async () => {
    const user = userEvent.setup();
    const keys: (string | null)[] = [];
    server.use(http.post(`${API}/transfers`, ({ request }) => {
      keys.push(request.headers.get("Idempotency-Key"));
      return HttpResponse.json({ code: "INSUFFICIENT_FUNDS", message: "Insufficient funds.", correlationId: "c", timestamp: "" }, { status: 422 });
    }));
    renderRoute(<NewTransferPage />);
    await fillAndReview(user);
    await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
    await screen.findByText("Insufficient funds.");

    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.click(await screen.findByRole("button", { name: "Review transfer" }));
    await user.click(await screen.findByRole("button", { name: "Confirm transfer" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).not.toBe(keys[0]);
  });

  describe("ambiguous failures", () => {
    const created = (id: string) => ({
      transferId: id, sourceAccountId: "ACC1001", destinationAccountId: "ACC1002", amount: "25.50",
      currency: "USD", status: "RECEIVED", createdAt: "", updatedAt: "",
    });

    it("keeps the same key after a network drop and blocks a fresh submit until resolved", async () => {
      const user = userEvent.setup();
      const keys: (string | null)[] = [];
      server.use(
        http.post(`${API}/transfers`, ({ request }) => { keys.push(request.headers.get("Idempotency-Key")); return HttpResponse.error(); }, { once: true }),
        http.post(`${API}/transfers`, ({ request }) => { keys.push(request.headers.get("Idempotency-Key")); return HttpResponse.json(created("t-net"), { status: 201 }); }),
      );
      renderRoute(<NewTransferPage />);
      await fillAndReview(user);
      await user.click(screen.getByRole("button", { name: "Confirm transfer" }));

      expect(await screen.findByText(/don't know yet whether this transfer was applied/)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Confirm transfer" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "Edit" })).toBeDisabled();
      expect(loadPendingTransfer(CUSTOMER_1.subject)?.idempotencyKey).toBe(keys[0]);

      await user.click(screen.getByRole("button", { name: "Retry same transfer" }));
      expect(await screen.findByText("Transfer detail page t-net")).toBeInTheDocument();
      expect(keys[1]).toBe(keys[0]);
      expect(loadPendingTransfer(CUSTOMER_1.subject)).toBeNull();
    });

    it("resumes an unconfirmed attempt after a reload with the identical key and body", async () => {
      const user = userEvent.setup();
      const body = { sourceAccountId: "ACC1001", destinationAccountId: "ACC1002", amount: "12.00", currency: "USD" };
      savePendingTransfer({ idempotencyKey: "key-from-before-reload", body, subject: CUSTOMER_1.subject, submittedAt: "" });
      let sent: { key: string | null; body: unknown } | undefined;
      server.use(http.post(`${API}/transfers`, async ({ request }) => {
        sent = { key: request.headers.get("Idempotency-Key"), body: await request.json() };
        return HttpResponse.json(created("t-replayed"), { status: 201, headers: { "Idempotent-Replayed": "true" } });
      }));

      renderRoute(<NewTransferPage />);
      expect(await screen.findByText(/couldn't confirm whether your last transfer went through/)).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Confirm transfer" }));

      expect(await screen.findByText("Transfer detail page t-replayed")).toBeInTheDocument();
      expect(sent).toEqual({ key: "key-from-before-reload", body });
    });

    it("ignores an unconfirmed attempt that belongs to a different user", async () => {
      savePendingTransfer({
        idempotencyKey: "someone-elses", subject: "customer-2", submittedAt: "",
        body: { sourceAccountId: "ACC2001", destinationAccountId: "ACC1001", amount: "5", currency: "USD" },
      });
      renderRoute(<NewTransferPage />);
      expect(await screen.findByRole("button", { name: "Review transfer" })).toBeInTheDocument();
      expect(screen.queryByText(/couldn't confirm/)).not.toBeInTheDocument();
    });

    it("waits out IDEMPOTENCY_IN_PROGRESS and retries automatically with the same key", async () => {
      const user = userEvent.setup();
      const keys: (string | null)[] = [];
      server.use(
        http.post(`${API}/transfers`, ({ request }) => {
          keys.push(request.headers.get("Idempotency-Key"));
          return HttpResponse.json(
            { code: "IDEMPOTENCY_IN_PROGRESS", message: "Still processing.", correlationId: "c", timestamp: "" },
            { status: 409, headers: { "Retry-After": "1" } },
          );
        }, { once: true }),
        http.post(`${API}/transfers`, ({ request }) => { keys.push(request.headers.get("Idempotency-Key")); return HttpResponse.json(created("t-wait"), { status: 201 }); }),
      );
      renderRoute(<NewTransferPage />);
      await fillAndReview(user);
      await user.click(screen.getByRole("button", { name: "Confirm transfer" }));

      expect(await screen.findByRole("button", { name: "Submitting…" })).toBeDisabled();
      expect(await screen.findByText("Transfer detail page t-wait", {}, { timeout: 3000 })).toBeInTheDocument();
      expect(keys).toHaveLength(2);
      expect(keys[1]).toBe(keys[0]);
    });

    it("follows the Location of an expired key to the original transfer instead of resubmitting", async () => {
      const user = userEvent.setup();
      server.use(http.post(`${API}/transfers`, () => HttpResponse.json(
        { code: "IDEMPOTENCY_KEY_EXPIRED", message: "Expired.", correlationId: "c", timestamp: "" },
        { status: 409, headers: { Location: "/api/v1/transfers/t-original" } },
      )));
      renderRoute(<NewTransferPage />);
      await fillAndReview(user);
      await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
      expect(await screen.findByText("Transfer detail page t-original")).toBeInTheDocument();
    });
  });

  describe("inactive accounts", () => {
    it("never offers a frozen account as the source", async () => {
      renderRoute(<NewTransferPage />);
      const select = await screen.findByLabelText("From account");
      expect(select).not.toHaveTextContent("1003"); // ACC1003 is FROZEN in the mock data
      expect(screen.getByText(/1 frozen or closed account is not/)).toBeInTheDocument();
    });

    it("ignores ?from= pointing at a frozen account", async () => {
      renderRoute(<NewTransferPage />, { path: "/transfers/new", url: "/transfers/new?from=ACC1003" });
      const select = (await screen.findByLabelText("From account")) as HTMLSelectElement;
      expect(select.value).not.toBe("ACC1003");
    });

    it("blocks the form entirely when no account is active", async () => {
      server.use(http.get(`${API}/accounts`, () => HttpResponse.json({ accounts: [
        { accountId: "ACC9", currency: "USD", status: "FROZEN", availableBalance: "1.00", ledgerBalance: "1.00" },
        { accountId: "ACC8", currency: "USD", status: "CLOSED", availableBalance: "0.00", ledgerBalance: "0.00" },
      ] })));
      renderRoute(<NewTransferPage />);
      expect(await screen.findByText(/None of your accounts can send transfers/)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Review transfer" })).not.toBeInTheDocument();
    });
  });
});
