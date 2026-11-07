import { beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderRoute } from "../../test/render";
import { mockNotifications, resetMockNotifications } from "../../mocks/data";
import { NotificationsPage } from "./NotificationsPage";
import { NotificationBell } from "../../components/AppShell";

const API = "http://api.test/api/v1";

function inbox() {
  return screen.getByRole("list", { name: "Notifications" });
}

describe("NotificationsPage", () => {
  beforeEach(() => resetMockNotifications());

  it("lists notifications newest first with rendered text and the unread count", async () => {
    renderRoute(<NotificationsPage />);
    const items = within(await screen.findByRole("list", { name: "Notifications" })).getAllByRole("listitem");
    expect(items.map((li) => li.getAttribute("aria-label"))).toEqual([
      "Unread: Transfer under review",
      "Unread: Money received",
      "New beneficiary saved",
    ]);
    expect(items[1]).toHaveTextContent(/You received .*60\.00 from •••• 2001\./);
    expect(items[2]).toHaveTextContent("If this wasn't you, contact us right away.");
    expect(screen.getByText("2 unread")).toBeInTheDocument();
  });

  it("never shows a full account number", async () => {
    renderRoute(<NotificationsPage />);
    await screen.findByRole("list", { name: "Notifications" });
    expect(inbox()).not.toHaveTextContent(/ACC\d{4}/);
  });

  it("filters by category and unread state", async () => {
    const user = userEvent.setup();
    renderRoute(<NotificationsPage />);
    await screen.findByRole("list", { name: "Notifications" });

    await user.click(screen.getByRole("button", { name: "Security" }));
    await waitFor(() => expect(within(inbox()).getAllByRole("listitem")).toHaveLength(1));
    expect(screen.getByRole("button", { name: "Security" })).toHaveAttribute("aria-pressed", "true");

    await user.click(screen.getByLabelText("Unread only"));
    expect(await screen.findByText("No unread notifications")).toBeInTheDocument();
  });

  it("marks one read: the unread marker and button go, and the count drops", async () => {
    const user = userEvent.setup();
    renderRoute(<NotificationsPage />);
    await screen.findByRole("list", { name: "Notifications" });

    await user.click(screen.getByRole("button", { name: "Mark as read: Money received" }));

    expect(await screen.findByText("1 unread")).toBeInTheDocument();
    expect(within(inbox()).getByRole("listitem", { name: "Money received" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Mark as read: Money received" })).not.toBeInTheDocument();
  });

  it("View opens the transfer and marks it read", async () => {
    const user = userEvent.setup();
    const received = mockNotifications.find((n) => n.type === "TRANSFER_RECEIVED")!;
    renderRoute(<NotificationsPage />);
    await screen.findByRole("list", { name: "Notifications" });

    await user.click(screen.getByRole("link", { name: "View: Money received" }));

    expect(await screen.findByText(`Transfer detail page ${received.entityId}`)).toBeInTheDocument();
    await waitFor(() => expect(received.readAt).not.toBeNull());
  });

  it("mark all as read clears the count and disables itself", async () => {
    const user = userEvent.setup();
    renderRoute(<NotificationsPage />);
    await screen.findByText("2 unread");

    await user.click(screen.getByRole("button", { name: "Mark all as read" }));

    expect(await screen.findByText("You're all caught up.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Mark all as read" })).toBeDisabled();
  });

  it("shows an empty state for a customer with no notifications", async () => {
    server.use(
      http.get(`${API}/notifications`, () =>
        HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0, unreadCount: 0 }),
      ),
    );
    renderRoute(<NotificationsPage />);
    expect(await screen.findByText("No notifications yet")).toBeInTheDocument();
  });

  it("shows a retryable error when the inbox fails to load", async () => {
    server.use(
      http.get(`${API}/notifications`, () =>
        HttpResponse.json({ code: "INTERNAL_ERROR", message: "Temporarily unavailable.", correlationId: "c", timestamp: "" }, { status: 500 }),
      ),
    );
    renderRoute(<NotificationsPage />);
    expect(await screen.findByText("Temporarily unavailable.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("renders an unknown notification type generically", async () => {
    server.use(
      http.get(`${API}/notifications`, () =>
        HttpResponse.json({
          items: [{ notificationId: "x", category: "ACCOUNT", type: "STATEMENT_READY", entityType: "TRANSFER", entityId: "t",
            params: {}, occurredAt: "2026-10-18T09:00:00Z", createdAt: "2026-10-18T09:00:00Z", readAt: null }],
          page: 0, size: 20, totalElements: 1, totalPages: 1, unreadCount: 1,
        }),
      ),
    );
    renderRoute(<NotificationsPage />);
    expect(await screen.findByText("You have a new notification.")).toBeInTheDocument();
  });
});

describe("NotificationBell", () => {
  beforeEach(() => resetMockNotifications());

  it("shows the unread count in the badge and the accessible name", async () => {
    renderRoute(<NotificationBell />);
    const bell = await screen.findByRole("link", { name: "Notifications, 2 unread" });
    expect(bell).toHaveAttribute("href", "/notifications");
    expect(bell).toHaveTextContent("2");
  });

  it("caps the badge at 99+", async () => {
    server.use(http.get(`${API}/notifications/unread-count`, () => HttpResponse.json({ unreadCount: 250 })));
    renderRoute(<NotificationBell />);
    expect(await screen.findByRole("link", { name: "Notifications, 250 unread" })).toHaveTextContent("99+");
  });

  it("hides the badge when the count fails, keeping the link", async () => {
    server.use(http.get(`${API}/notifications/unread-count`, () => new HttpResponse(null, { status: 500 })));
    renderRoute(<NotificationBell />);
    const bell = await screen.findByRole("link", { name: "Notifications" });
    expect(bell.querySelector(".bell__badge")).toBeNull();
  });
});
