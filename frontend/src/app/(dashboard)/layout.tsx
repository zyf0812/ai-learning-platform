"use client";

import { useEffect, useState } from "react";
import { ThemeProvider } from "@/components/theme-context";
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
import {
  BarChart3,
  BookOpenCheck,
  Brain,
  ChevronLeft,
  ClipboardCheck,
  FileText,
  Home,
  Layers3,
  LogOut,
  Menu,
  MessageSquareText,
  Settings,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
  UserRound,
  UsersRound,
} from "lucide-react";
import Link from "next/link";

const NAV_ITEMS = [
  { href: "/", label: "首页", icon: Home },
  { href: "/documents", label: "文档", icon: FileText },
  { href: "/chat", label: "AI助手", icon: MessageSquareText },
  { href: "/flashcards", label: "闪卡", icon: Layers3 },
  { href: "/exams", label: "考试", icon: ClipboardCheck },
  { href: "/wrong-questions", label: "错题", icon: TriangleAlert },
  { href: "/groups", label: "群组", icon: UsersRound },
  { href: "/stats", label: "统计", icon: BarChart3 },
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
  }, [loading, user, router]);

  useEffect(() => {
    queueMicrotask(() => setMobileOpen(false));
  }, [pathname]);

  if (loading) {
    return (
      <div className="app-shell flex min-h-screen items-center justify-center">
        <div className="surface-panel flex w-72 flex-col items-center rounded-2xl p-8">
          <div className="mb-5 flex size-12 items-center justify-center rounded-2xl bg-emerald-600 text-white shadow-lg shadow-emerald-900/10">
            <Brain className="size-6 animate-pulse" />
          </div>
          <div className="w-full space-y-2">
            <Skeleton className="mx-auto h-3 w-44 rounded-full" />
            <Skeleton className="mx-auto h-3 w-28 rounded-full" />
          </div>
        </div>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="app-shell flex min-h-screen items-center justify-center text-sm text-muted-foreground">
        请先登录
      </div>
    );
  }

  const isChat = pathname.startsWith("/chat");

  return (
    <ThemeProvider>
    <div className="app-shell h-screen overflow-hidden">
      <div className="relative z-10 flex h-full flex-col">
        <header className="glass sticky top-0 z-20">
          <div className="mx-auto flex h-16 items-center justify-between px-4 lg:px-6">
            <div className="flex min-w-0 items-center gap-2">
              <button
                onClick={() => router.back()}
                className="mr-1 rounded-lg p-2 text-muted-foreground transition-colors hover:bg-muted lg:hidden"
                aria-label="返回"
              >
                <ChevronLeft className="size-4" />
              </button>

              <Link href="/" className="mr-4 flex shrink-0 items-center gap-2.5">
                <div className="flex size-9 items-center justify-center rounded-xl bg-slate-950 text-white shadow-lg shadow-slate-900/10">
                  <Sparkles className="size-4" />
                </div>
                <div className="hidden leading-tight sm:block">
                  <div className="text-sm font-bold text-foreground">AI 学习平台</div>
                  <div className="text-[11px] text-muted-foreground">文档驱动的学习空间</div>
                </div>
              </Link>

              <nav className="hidden items-center gap-1 lg:flex">
                {NAV_ITEMS.map((item) => {
                  const isActive = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                  const Icon = item.icon;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      className={`flex items-center gap-1.5 rounded-xl px-3 py-2 text-sm font-medium transition-all duration-150 ${
                        isActive
                          ? "bg-slate-950 text-white shadow-sm shadow-slate-900/10"
                          : "text-muted-foreground hover:bg-white/70 hover:text-foreground dark:hover:bg-white/10"
                      }`}
                    >
                      <Icon className="size-4" />
                      {item.label}
                    </Link>
                  );
                })}
              </nav>
            </div>

            <div className="flex items-center gap-2">
              <NotificationBell />

              <Button
                variant="ghost"
                size="icon"
                className="lg:hidden"
                onClick={() => setMobileOpen(!mobileOpen)}
                aria-label="打开导航"
              >
                <Menu className="size-5" />
              </Button>

              <DropdownMenu>
                <DropdownMenuTrigger className="flex cursor-pointer items-center gap-2 rounded-xl border border-border/70 bg-white/70 px-2 py-1.5 shadow-sm transition hover:bg-white dark:bg-white/10 dark:hover:bg-white/15">
                  <div className="flex size-8 items-center justify-center rounded-lg bg-emerald-600 text-xs font-bold text-white">
                    {user.username.charAt(0).toUpperCase()}
                  </div>
                  <div className="hidden max-w-[120px] text-left sm:block">
                    <div className="truncate text-sm font-semibold text-foreground">{user.username}</div>
                    <div className="text-[11px] text-muted-foreground">{user.role || "user"}</div>
                  </div>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-48 rounded-xl p-1">
                  <div className="mb-1 flex items-center gap-2 border-b px-3 py-2 text-xs text-muted-foreground">
                    <UserRound className="size-4" />
                    <span className="truncate">{user.username}</span>
                  </div>
                  {user.role === "root" && (
                    <DropdownMenuItem onClick={() => router.push("/root")} className="cursor-pointer rounded-lg text-sm">
                      <ShieldCheck className="mr-2 size-4" /> Root 控制台
                    </DropdownMenuItem>
                  )}
                  {(user.role === "admin" || user.role === "root") && (
                    <DropdownMenuItem onClick={() => router.push("/admin")} className="cursor-pointer rounded-lg text-sm">
                      <BookOpenCheck className="mr-2 size-4" /> 管理面板
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem onClick={() => router.push("/stats")} className="cursor-pointer rounded-lg text-sm">
                    <BarChart3 className="mr-2 size-4" /> 学习统计
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setSettingsOpen(true)} className="cursor-pointer rounded-lg text-sm">
                    <Settings className="mr-2 size-4" /> 个性化设置
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setJoinSupervisionOpen(true)} className="cursor-pointer rounded-lg text-sm">
                    <UsersRound className="mr-2 size-4" /> 加入监管
                  </DropdownMenuItem>
                  <div className="mt-1 border-t pt-1">
                    <DropdownMenuItem onClick={logout} className="cursor-pointer rounded-lg text-sm text-red-500 focus:text-red-500">
                      <LogOut className="mr-2 size-4" /> 退出登录
                    </DropdownMenuItem>
                  </div>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          </div>

          {mobileOpen && (
            <div className="slide-up border-t bg-card/95 px-3 pb-3 pt-2 backdrop-blur-lg lg:hidden">
              <div className="grid grid-cols-2 gap-1">
                {NAV_ITEMS.map((item) => {
                  const isActive = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
                  const Icon = item.icon;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      className={`flex items-center gap-2 rounded-xl px-3 py-2.5 text-sm transition-colors ${
                        isActive ? "bg-slate-950 text-white" : "text-foreground hover:bg-muted"
                      }`}
                    >
                      <Icon className="size-4 shrink-0" />
                      {item.label}
                    </Link>
                  );
                })}
              </div>
            </div>
          )}
        </header>

        <main className={`mx-auto w-full max-w-7xl flex-1 px-4 lg:px-6 ${isChat ? "overflow-hidden py-0" : "overflow-auto py-6 lg:py-8"}`}>
          {children}
        </main>
      </div>

      <SettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)} />
      <JoinSupervisionDialog open={joinSupervisionOpen} onClose={() => setJoinSupervisionOpen(false)} />
    </div>
    </ThemeProvider>
  );
}
