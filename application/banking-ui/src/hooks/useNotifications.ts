import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notificationsApi, type NotificationQuery } from "../api/notifications";

const KEY = ["notifications"] as const;

/** Polling cadence for the badge (N4): at most once a minute, plus on window focus. */
export const UNREAD_POLL_MS = 60_000;

export function useNotifications(query: NotificationQuery) {
  return useQuery({
    queryKey: [...KEY, "list", query],
    queryFn: () => notificationsApi.list(query),
    placeholderData: keepPreviousData,
  });
}

export function useUnreadCount() {
  return useQuery({
    queryKey: [...KEY, "unread-count"],
    queryFn: notificationsApi.unreadCount,
    refetchInterval: UNREAD_POLL_MS,
    refetchOnWindowFocus: true,
  });
}

function useInvalidateNotifications() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: KEY });
}

export function useMarkRead() {
  const invalidate = useInvalidateNotifications();
  return useMutation({ mutationFn: (notificationId: string) => notificationsApi.markRead(notificationId), onSettled: invalidate });
}

export function useMarkAllRead() {
  const invalidate = useInvalidateNotifications();
  return useMutation({ mutationFn: () => notificationsApi.markAllRead(), onSettled: invalidate });
}
