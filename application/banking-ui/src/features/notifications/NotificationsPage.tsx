import { useState } from "react";
import { Link } from "react-router-dom";
import type { AppNotification, NotificationCategory } from "../../api/types";
import { useMarkAllRead, useMarkRead, useNotifications } from "../../hooks/useNotifications";
import { EmptyState } from "../../components/EmptyState";
import { ErrorPanel } from "../../components/ErrorPanel";
import { Pagination } from "../../components/Pagination";
import { SkeletonCard } from "../../components/Skeleton";
import { describeNotification } from "./messages";

const FILTERS: { label: string; category?: NotificationCategory }[] = [
  { label: "All" },
  { label: "Transactions", category: "TRANSACTION" },
  { label: "Security", category: "SECURITY" },
];

export function NotificationsPage() {
  const [page, setPage] = useState(0);
  const [category, setCategory] = useState<NotificationCategory | undefined>();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const query = useNotifications({ page, category, unreadOnly });
  const markAll = useMarkAllRead();
  const data = query.data;
  const unread = data?.unreadCount ?? 0;

  return (
    <div className="stack" style={{ gap: "var(--space-3)", maxWidth: 720 }}>
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: "var(--space-2)" }}>
        <div className="stack stack--tight">
          <h1 className="page-title">Notifications</h1>
          <p className="metadata">
            {unread === 0 ? "You're all caught up." : `${unread} unread`}
          </p>
        </div>
        <button
          type="button"
          className="btn btn--secondary"
          disabled={unread === 0 || markAll.isPending}
          onClick={() => markAll.mutate()}
        >
          {markAll.isPending ? "Marking…" : "Mark all as read"}
        </button>
      </div>
      {markAll.isError && <ErrorPanel error={markAll.error} />}

      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: "var(--space-2)" }}>
        <div className="segmented" role="group" aria-label="Filter by category">
          {FILTERS.map((f) => (
            <button
              key={f.label}
              type="button"
              className={`segmented__option${f.category === category ? " segmented__option--active" : ""}`}
              aria-pressed={f.category === category}
              onClick={() => {
                setCategory(f.category);
                setPage(0);
              }}
            >
              {f.label}
            </button>
          ))}
        </div>
        <label className="row metadata" style={{ gap: 6 }}>
          <input
            type="checkbox"
            checked={unreadOnly}
            onChange={(e) => {
              setUnreadOnly(e.target.checked);
              setPage(0);
            }}
          />
          Unread only
        </label>
      </div>

      {query.isLoading && (
        <div className="stack">
          <SkeletonCard />
          <SkeletonCard />
        </div>
      )}
      {query.isError && <ErrorPanel error={query.error} onRetry={() => query.refetch()} />}
      {query.isSuccess && data && data.items.length === 0 && (
        <EmptyState
          title={unreadOnly ? "No unread notifications" : "No notifications yet"}
          description={
            unreadOnly ? "You're all caught up." : "We'll let you know here about transfers and changes to your beneficiaries."
          }
        />
      )}
      {query.isSuccess && data && data.items.length > 0 && (
        <>
          <ul className="stack" style={{ listStyle: "none", margin: 0, padding: 0 }} aria-label="Notifications">
            {data.items.map((n) => (
              <NotificationItem key={n.notificationId} notification={n} />
            ))}
          </ul>
          <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </div>
  );
}

function NotificationItem({ notification }: { notification: AppNotification }) {
  const markRead = useMarkRead();
  const { title, body, href } = describeNotification(notification);
  const unread = notification.readAt === null;
  const markIfUnread = () => {
    if (unread) markRead.mutate(notification.notificationId);
  };

  return (
    <li
      className={`card stack stack--tight notification${unread ? " notification--unread" : ""}`}
      aria-label={`${unread ? "Unread: " : ""}${title}`}
    >
      <div className="row" style={{ justifyContent: "space-between", gap: "var(--space-2)", alignItems: "flex-start" }}>
        <div className="stack stack--tight" style={{ minWidth: 0 }}>
          <span className="card-title" style={{ color: "var(--color-text)" }}>
            {unread && <span className="notification__dot" aria-hidden="true" />}
            {title}
          </span>
          <span>{body}</span>
          <time className="metadata" dateTime={notification.occurredAt}>
            {new Date(notification.occurredAt).toLocaleString()}
          </time>
        </div>
        <div className="row" style={{ gap: "var(--space-1)", flexShrink: 0 }}>
          {href && (
            <Link className="btn btn--secondary" to={href} onClick={markIfUnread} aria-label={`View: ${title}`}>
              View
            </Link>
          )}
          {unread && (
            <button
              type="button"
              className="btn btn--ghost"
              disabled={markRead.isPending}
              onClick={markIfUnread}
              aria-label={`Mark as read: ${title}`}
            >
              Mark as read
            </button>
          )}
        </div>
      </div>
      {markRead.isError && <ErrorPanel error={markRead.error} />}
    </li>
  );
}
