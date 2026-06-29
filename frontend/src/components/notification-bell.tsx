"use client";

import { useState, useEffect } from "react";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { api } from "@/lib/api";

export function NotificationBell() {
  const [notifs, setNotifs] = useState<any[]>([]);
  const [unread, setUnread] = useState(0);

  const load = async () => {
    try {
      const d = await api.notifications.list();
      setNotifs(d.notifications || []);
      setUnread(d.unread || 0);
    } catch {}
  };

  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, []);

  const markRead = async (id: string) => {
    await api.notifications.read(id);
    load();
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger className="relative cursor-pointer p-1.5 rounded-lg hover:bg-gray-100">
        <svg className="w-5 h-5 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M14.857 17.082a23.848 23.848 0 005.454-1.31A8.967 8.967 0 0118 9.75v-.7V9A6 6 0 006 9v.75a8.967 8.967 0 01-2.312 6.022c1.733.64 3.56 1.085 5.455 1.31m5.714 0a24.255 24.255 0 01-5.714 0m5.714 0a3 3 0 11-5.714 0" />
        </svg>
        {unread > 0 && (
          <span className="absolute -top-0.5 -right-0.5 w-4 h-4 bg-red-500 text-white text-[10px] rounded-full flex items-center justify-center font-bold">
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 max-h-96 overflow-y-auto">
        <div className="px-3 py-2 border-b text-xs font-semibold text-gray-500">通知</div>
        {notifs.length === 0 ? (
          <div className="px-3 py-4 text-sm text-gray-400 text-center">暂无通知</div>
        ) : (
          notifs.map(n => (
            <DropdownMenuItem key={n.id} className="flex flex-col items-start py-2 px-3 cursor-default" onClick={() => markRead(n.id)}>
              <div className="flex items-center gap-2 w-full">
                {!n.isRead && <span className="w-2 h-2 bg-blue-500 rounded-full shrink-0" />}
                <span className="font-medium text-sm">{n.title}</span>
                {n.isBroadcast && <span className="text-[10px] bg-red-100 text-red-600 px-1 rounded">广播</span>}
              </div>
              <p className="text-xs text-gray-500 mt-0.5">{n.content?.slice(0, 80)}</p>
            </DropdownMenuItem>
          ))
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
