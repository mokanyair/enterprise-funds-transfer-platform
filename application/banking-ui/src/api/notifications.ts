import { http } from "./http";
import type { NotificationCategory, NotificationPage, UnreadCount } from "./types";

export interface NotificationQuery {
  page: number;
  size?: number;
  category?: NotificationCategory;
  unreadOnly?: boolean;
}

export const notificationsApi = {
  async list({ page, size = 20, category, unreadOnly }: NotificationQuery): Promise<NotificationPage> {
    const { data } = await http.get<NotificationPage>("/notifications", {
      params: { page, size, ...(category && { category }), ...(unreadOnly && { unreadOnly: true }) },
    });
    return data;
  },

  async unreadCount(): Promise<UnreadCount> {
    const { data } = await http.get<UnreadCount>("/notifications/unread-count");
    return data;
  },

  /** Idempotent: an already read notification is 204 again (N5). */
  async markRead(notificationId: string): Promise<void> {
    await http.post(`/notifications/${encodeURIComponent(notificationId)}/read`);
  },

  /** Marks what is unread now; one arriving during the request stays unread. */
  async markAllRead(): Promise<void> {
    await http.post("/notifications/read-all");
  },
};
