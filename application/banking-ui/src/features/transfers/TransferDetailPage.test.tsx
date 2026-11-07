import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderRoute } from "../../test/render";
import { TransferDetailPage } from "./TransferDetailPage";
import type { Transfer, TransferStatus } from "../../api/types";

const API = "http://api.test/api/v1";
const transfer = (status: TransferStatus): Transfer => ({
  transferId: "t-1", sourceAccountId: "ACC1001", destinationAccountId: "ACC1002", amount: "15000.0000",
  currency: "USD", status, createdAt: "2026-09-26T10:00:00Z", updatedAt: "2026-09-26T10:00:00Z",
});

function renderDetail() {
  return renderRoute(<TransferDetailPage />, { path: "/transfers/:transferId", url: "/transfers/t-1" });
}

describe("TransferDetailPage cancel", () => {
  it("asks for confirmation and 'Keep transfer' sends nothing", async () => {
    const user = userEvent.setup();
    let cancelCalls = 0;
    server.use(
      http.get(`${API}/transfers/t-1`, () => HttpResponse.json(transfer("PENDING_REVIEW"))),
      http.post(`${API}/transfers/t-1/cancel`, () => { cancelCalls += 1; return HttpResponse.json(transfer("CANCELLED")); }),
    );
    renderDetail();
    await user.click(await screen.findByRole("button", { name: "Cancel transfer" }));

    const dialog = screen.getByRole("alertdialog", { name: "Cancel this transfer?" });
    expect(dialog).toHaveTextContent("$15,000.00");
    expect(dialog).toHaveTextContent("•••• 1002");
    await user.click(screen.getByRole("button", { name: "Keep transfer" }));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(cancelCalls).toBe(0);
  });

  it("cancels on confirm and refetches balances", async () => {
    const user = userEvent.setup();
    let status: TransferStatus = "PENDING_REVIEW";
    server.use(
      http.get(`${API}/transfers/t-1`, () => HttpResponse.json(transfer(status))),
      http.post(`${API}/transfers/t-1/cancel`, () => { status = "CANCELLED"; return HttpResponse.json(transfer(status)); }),
    );
    const { queryClient } = renderDetail();
    queryClient.setQueryData(["accounts"], { accounts: [] }); // stands in for a cached balance list
    await user.click(await screen.findByRole("button", { name: "Cancel transfer" }));
    await user.click(screen.getByRole("button", { name: "Yes, cancel transfer" }));

    expect(await screen.findByText("Cancelled")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Cancel transfer/ })).not.toBeInTheDocument();
    expect(queryClient.getQueryState(["accounts"])?.isInvalidated).toBe(true);
  });

  it("shows the real status when the transfer was already posted", async () => {
    const user = userEvent.setup();
    let status: TransferStatus = "PENDING_REVIEW";
    server.use(
      http.get(`${API}/transfers/t-1`, () => HttpResponse.json(transfer(status))),
      http.post(`${API}/transfers/t-1/cancel`, () => {
        status = "PROCESSING";
        return HttpResponse.json(
          { code: "TRANSFER_NOT_CANCELLABLE", message: "The transfer has already been posted and cannot be cancelled.", correlationId: "c", timestamp: "" },
          { status: 409 },
        );
      }),
    );
    renderDetail();
    await user.click(await screen.findByRole("button", { name: "Cancel transfer" }));
    await user.click(screen.getByRole("button", { name: "Yes, cancel transfer" }));

    expect(await screen.findByText(/already been posted/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("Processing")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Cancel transfer" })).not.toBeInTheDocument();
  });
});
