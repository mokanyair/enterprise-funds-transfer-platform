import { beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderRoute } from "../../test/render";
import { mockBeneficiaries, resetMockBeneficiaries } from "../../mocks/data";
import { BeneficiariesPage } from "./BeneficiariesPage";

const API = "http://api.test/api/v1";

function savedList() {
  return screen.getByRole("list", { name: "Saved beneficiaries" });
}

async function add(user: ReturnType<typeof userEvent.setup>, accountId: string, nickname: string) {
  await user.type(screen.getByLabelText("Account number"), accountId);
  await user.type(screen.getByLabelText("Nickname"), nickname);
  await user.click(screen.getByRole("button", { name: "Save beneficiary" }));
}

describe("BeneficiariesPage", () => {
  beforeEach(() => resetMockBeneficiaries());

  it("lists saved beneficiaries with masked account numbers and a send-money shortcut", async () => {
    renderRoute(<BeneficiariesPage />);
    const row = (await screen.findByText("Sam - shared rent")).closest("li")!;
    expect(row).toHaveTextContent("•••• 2001");
    expect(row).not.toHaveTextContent("ACC2001");
    expect(within(row).getByRole("link", { name: "Send money to Sam - shared rent" })).toHaveAttribute(
      "href",
      "/transfers/new?to=ACC2001",
    );
  });

  it("shows an empty state when nothing is saved", async () => {
    server.use(http.get(`${API}/beneficiaries`, () => HttpResponse.json({ beneficiaries: [] })));
    renderRoute(<BeneficiariesPage />);
    expect(await screen.findByText("No saved beneficiaries yet")).toBeInTheDocument();
  });

  it("shows a retryable error when the list fails to load", async () => {
    server.use(
      http.get(`${API}/beneficiaries`, () =>
        HttpResponse.json({ code: "INTERNAL_ERROR", message: "Temporarily unavailable.", correlationId: "c", timestamp: "" }, { status: 500 }),
      ),
    );
    renderRoute(<BeneficiariesPage />);
    expect(await screen.findByText("Temporarily unavailable.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("adds a beneficiary with the trimmed nickname and shows it in the list", async () => {
    const user = userEvent.setup();
    const bodies: unknown[] = [];
    server.events.on("request:start", async ({ request }) => {
      if (request.method === "POST") bodies.push(await request.clone().json());
    });
    renderRoute(<BeneficiariesPage />);
    await screen.findByText("Sam - shared rent");

    await add(user, " ACC2002 ", "  Alex - savings ");

    expect(await within(savedList()).findByText("Alex - savings")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Saved Alex - savings.");
    expect(bodies).toEqual([{ accountId: "ACC2002", nickname: "Alex - savings" }]);
    expect(screen.getByLabelText("Account number")).toHaveValue("");
    server.events.removeAllListeners();
  });

  it("validates locally and never posts an invalid form", async () => {
    const user = userEvent.setup();
    let posted = false;
    server.use(http.post(`${API}/beneficiaries`, () => { posted = true; return HttpResponse.json({}); }));
    renderRoute(<BeneficiariesPage />);
    await screen.findByText("Sam - shared rent");

    await user.click(screen.getByRole("button", { name: "Save beneficiary" }));
    expect(screen.getByText("Enter the recipient's account number.")).toBeInTheDocument();
    expect(screen.getByLabelText("Account number")).toHaveAttribute("aria-invalid", "true");

    await add(user, "ACC1001", "Me"); // own account, known from GET /accounts
    expect(await screen.findByText(/one of your own accounts/)).toBeInTheDocument();
    expect(posted).toBe(false);
  });

  it("treats an already-saved account as done, not as a failure", async () => {
    const user = userEvent.setup();
    renderRoute(<BeneficiariesPage />);
    await screen.findByText("Sam - shared rent");

    await add(user, "ACC2001", "Sam again");

    expect(await screen.findByText("That account is already in your saved beneficiaries.")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(within(savedList()).getAllByRole("listitem")).toHaveLength(1);
  });

  it("shows the server's message for business rejections", async () => {
    const user = userEvent.setup();
    renderRoute(<BeneficiariesPage />);
    await screen.findByText("Sam - shared rent");

    await add(user, "NOSUCH1", "Ghost");
    expect(await screen.findByRole("alert")).toHaveTextContent("One of the accounts is not active.");
  });

  it("asks before deleting, then removes the beneficiary", async () => {
    const user = userEvent.setup();
    const deleted: string[] = [];
    server.events.on("request:start", ({ request }) => {
      if (request.method === "DELETE") deleted.push(new URL(request.url).pathname);
    });
    renderRoute(<BeneficiariesPage />);
    await user.click(await screen.findByRole("button", { name: "Delete Sam - shared rent" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Past and pending transfers aren't affected.");
    await user.click(screen.getByRole("button", { name: "Keep" }));
    expect(deleted).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Delete Sam - shared rent" }));
    await user.click(screen.getByRole("button", { name: "Delete beneficiary" }));
    expect(await screen.findByText("No saved beneficiaries yet")).toBeInTheDocument();
    expect(deleted).toEqual([`/api/v1/beneficiaries/${mockBeneficiaries[0].beneficiaryId}`]);
    server.events.removeAllListeners();
  });

  it("renders nicknames as text, never as markup", async () => {
    server.use(
      http.get(`${API}/beneficiaries`, () =>
        HttpResponse.json({
          beneficiaries: [{ beneficiaryId: "b-1", accountId: "ACC9999", nickname: "<img src=x onerror=alert(1)>", createdAt: "" }],
        }),
      ),
    );
    const { container } = renderRoute(<BeneficiariesPage />);
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    await waitFor(() => expect(container.querySelector("img")).toBeNull());
  });
});
