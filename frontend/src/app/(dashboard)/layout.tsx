"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/components/auth-context";
import { useRouter, usePathname } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { NotificationBell } from "@/components/notification-bell";
import { SettingsDialog } from "@/components/settings-dialog";
import { JoinSupervisionDialog } from "@/components/join-supervision-dialog";
import Link from "next/link";

const NAV_ITEMS = [
  { href: "/", label: "首页", icon: "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-4 0a1 1 0 01-1-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 01-1 1" },
  { href: "/documents", label: "文档", icon: "M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" },
  { href: "/chat", label: "AI助手", icon: "M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" },
  { href: "/flashcards", label: "闪卡", icon: "M4 6h16M4 12h16m-7 6h7" },
  { href: "/exams", label: "考试", icon: "M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" },
  { href: "/wrong-questions", label: "错题", icon: "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" },
  { href: "/groups", label: "群组", icon: "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" },
  { href: "/stats", label: "统计", icon: "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" },
];

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [joinSupervisionOpen, setJoinSupervisionOpen] = useState(false);

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, user]);

  useEffect(() => { setMobileOpen(false); }, [pathname]);

  if (loading) return (
    <div className="flex items-center justify-center min-h-screen bg-background">
      <div className="flex flex-col items-center gap-4">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center shadow-lg shadow-blue-500/20">
          <svg className="w-5 h-5 text-white animate-pulse" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
          </svg>
        </div>
        <div className="space-y-2 w-48">
          <Skeleton className="h-3 w-full rounded-full" />
          <Skeleton className="h-3 w-2/3 mx-auto rounded-full" />
        </div>
      </div>
    </div>
  );
  if (!user) return <div className="flex items-center justify-center min-h-screen bg-background text-muted-foreground text-sm">请登录</div>;

  const isChat = pathname.startsWith("/chat");

  return (
    <div className="h-screen flex flex-col bg-background">
      <header className="glass sticky top-0 z-20">
        <div className="mx-auto px-4 lg:px-6 h-14 flex items-center justify-between">
          <div className="flex items-center gap-1">
            <button onClick={() => router.back()} className="lg:hidden mr-1 p-2 rounded-lg hover:bg-muted text-muted-foreground transition-colors">
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                <path d="M19 12H5M12 19l-7-7 7-7" />
              </svg>
            </button>
            <Link href="/" className="flex items-center gap-2 mr-4 shrink-0">
              <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center shadow-sm">
                <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
                </svg>
              </div>
              <span className="font-semibold text-sm tracking-tight hidden sm:inline">AI 学习平台</span>
            </Link>

            <nav className="hidden lg:flex items-center gap-0.5">
              {NAV_ITEMS.map(item => {
                const isActive = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-all duration-150 ${
                      isActive
                        ? "text-primary bg-primary/10 shadow-sm"
                        : "text-muted-foreground hover:text-foreground hover:bg-muted/60"
                    }`}
                  >
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                      <path d={item.icon} />
                    </svg>
                    {item.label}
                  </Link>
                );
              })}
            </nav>
          </div>

          <div className="flex items-center gap-1.5">
            <NotificationBell />

            <Button
              variant="ghost"
              size="sm"
              className="lg:hidden text-muted-foreground hover:bg-muted/60"
              onClick={() => setMobileOpen(!mobileOpen)}
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </Button>

            <DropdownMenu>
              <DropdownMenuTrigger className="flex items-center gap-2 cursor-pointer hover:opacity-80 transition-opacity px-1.5 py-1 rounded-lg hover:bg-muted/60">
                <div className="w-7 h-7 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white text-xs font-medium shadow-sm">
                  {user.username.charAt(0).toUpperCase()}
                </div>
                <span className="text-sm text-foreground hidden sm:inline max-w-[100px] truncate">{user.username}</span>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-44 rounded-xl p-1">
                <div className="px-3 py-2 border-b mb-1 text-xs text-muted-foreground flex items-center gap-2">
                  <div className="w-6 h-6 rounded-full bg-gradient-to-br from-blue-400 to-blue-600 flex items-center justify-center text-white text-[10px] font-medium">
                    {user.username.charAt(0).toUpperCase()}
                  </div>
                  {user.username}
                  <span className="ml-auto text-[10px] bg-muted px-1.5 py-0.5 rounded-full">{user.role || "user"}</span>
                </div>
                {user.role === "root" && (
                  <DropdownMenuItem onClick={() => router.push("/root")} className="cursor-pointer rounded-lg text-sm">
                    🛡️ Root 控制台
                  </DropdownMenuItem>
                )}
                {(user.role === "admin" || user.role === "root") && (
                  <DropdownMenuItem onClick={() => router.push("/admin")} className="cursor-pointer rounded-lg text-sm">
                    👥 管理面板
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem onClick={() => router.push("/stats")} className="cursor-pointer rounded-lg text-sm">
                  📊 学习统计
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => setSettingsOpen(true)} className="cursor-pointer rounded-lg text-sm">
                  ⚙️ 个性化设置
                </DropdownMenuItem>
                <DropdownMenuItem onClick={() => setJoinSupervisionOpen(true)} className="cursor-pointer rounded-lg text-sm">
                  🔍 加入监管
                </DropdownMenuItem>
                <div className="border-t mt-1 pt-1">
                  <DropdownMenuItem onClick={logout} className="cursor-pointer rounded-lg text-sm text-red-500 focus:text-red-500">
                    退出登录
                  </DropdownMenuItem>
                </div>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>

        {mobileOpen && (
          <div className="lg:hidden border-t bg-card/95 backdrop-blur-lg px-3 pb-3 pt-2 space-y-0.5 slide-up">
            {NAV_ITEMS.map(item => {
              const isActive = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  onClick={() => setMobileOpen(false)}
                  className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors ${
                    isActive ? "bg-primary/10 text-primary font-medium" : "text-foreground hover:bg-muted"
                  }`}
                >
                  <svg className="w-4 h-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                    <path d={item.icon} />
                  </svg>
                  {item.label}
                </Link>
              );
            })}
          </div>
        )}
      </header>

      <main className={`flex-1 max-w-7xl w-full mx-auto px-4 lg:px-6 ${isChat ? "py-0 overflow-hidden" : "py-6 lg:py-8 overflow-auto"}`}>{children}</main>

      <SettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
      <JoinSupervisionDialog open={joinSupervisionOpen} onClose={() => setJoinSupervisionOpen(false)} />
    </div>
  );
}
