import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse, delay } from "msw";
import { server } from "../../test/server";
import { renderRoute } from "../../test/render";
import { DashboardPage } from "./DashboardPage";

const API = "http://api.test/api/v1";

describe("DashboardPage", () => {
  it("never shows a placeholder zero balance while accounts load", async () => {
    server.use(http.get(`${API}/accounts`, async () => { await delay("infinite"); return HttpResponse.json({}); }));
    const { container } = renderRoute(<DashboardPage />);
    expect(container.querySelector(".skeleton")).not.toBeNull();
    expect(container).not.toHaveTextContent("$0.00");
  });

  it("renders backend balances with masked account numbers", async () => {
    renderRoute(<DashboardPage />);
    expect(await screen.findByText("$2,450.75")).toBeInTheDocument();
    expect(screen.getAllByText("•••• 1001").length).toBeGreaterThan(0);
    expect(document.body).not.toHaveTextContent("ACC1001");
  });

  it("shows a safe error when accounts fail to load", async () => {
    server.use(http.get(`${API}/accounts`, () =>
      HttpResponse.json({ code: "INTERNAL_ERROR", message: "Something went wrong. Please try again.", correlationId: "c", timestamp: "" }, { status: 500 })));
    renderRoute(<DashboardPage />);
    expect((await screen.findAllByRole("alert"))[0]).toHaveTextContent("Something went wrong");
  });
});
