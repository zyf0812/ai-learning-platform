"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams, useRouter, usePathname } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ConfirmDialog } from "@/components/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

interface Conv {
  id: string;
  title: string;
  createdAt: string;
}

export default function ChatLayout({ children }: { children: React.ReactNode }) {
  const [convs, setConvs] = useState<Conv[]>([]);
  const [loading, setLoading] = useState(true);
  const [newOpen, setNewOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const router = useRouter();
  const pathname = usePathname();
  const params = useParams();
  const activeId = params?.id as string;

  const load = useCallback(async () => {
    try {
      const data = await api.conversations.list();
      setConvs(data.conversations || []);
    } catch {}
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  useEffect(() => { setSidebarOpen(false); }, [pathname]);

  const create = async () => {
    try {
      const data = await api.conversations.create({ title: title.trim() || "新对话" });
      if (!data.conversation?.id) return;
      setNewOpen(false);
      setTitle("");
      load();
      router.push(`/chat/${data.conversation.id}`);
    } catch { return; }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await api.conversations.delete(deleteTarget);
    if (activeId === deleteTarget) router.push("/chat");
    setDeleteTarget(null);
    load();
  };

  return (
    <div className="flex h-full">
      {sidebarOpen && (
        <div className="fixed inset-0 bg-black/30 z-10 md:hidden fade-in" onClick={() => setSidebarOpen(false)} />
      )}
      <div className={`${sidebarOpen ? 'fixed left-0 z-20 h-full slide-up' : 'hidden'} md:static md:flex w-64 border-r bg-card flex-col shrink-0`}>
        <div className="p-3 border-b flex items-center gap-2">
          <Button variant="ghost" size="sm" className="md:hidden shrink-0 text-muted-foreground" onClick={() => setSidebarOpen(false)}>
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path d="M19 12H5M12 19l-7-7 7-7" /></svg>
          </Button>
          <Button className="flex-1 h-9 text-sm gap-1.5" size="sm" onClick={() => { setNewOpen(true); setSidebarOpen(false); }}>
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path d="M12 4v16m8-8H4" /></svg>
            新建对话
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto py-1">
          {loading ? (
            <div className="p-4 space-y-2">
              {Array.from({length:3}).map((_,i)=><Skeleton key={i} className="h-12 w-full rounded-lg" />)}
            </div>
          ) : convs.length === 0 ? (
            <div className="p-6 text-center">
              <p className="text-sm text-muted-foreground">暂无对话</p>
              <p className="text-xs text-muted-foreground/60 mt-1">点击上方按钮开始新对话</p>
            </div>
          ) : (
            convs.map((c) => (
              <div
                key={c.id}
                onClick={() => router.push(`/chat/${c.id}`)}
                className={`mx-1 px-3 py-2.5 rounded-lg cursor-pointer text-sm group flex items-center justify-between transition-colors ${
                  activeId === c.id
                    ? "bg-primary/10 text-primary font-medium"
                    : "hover:bg-muted/60 text-foreground"
                }`}
              >
                <div className="flex-1 min-w-0">
                  <p className="truncate">{c.title}</p>
                  <p className="text-[11px] text-muted-foreground mt-0.5">
                    {new Date(c.createdAt).toLocaleDateString("zh-CN")}
                  </p>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger
                    className="opacity-0 group-hover:opacity-100 p-1 hover:bg-muted rounded-md transition-opacity"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                      <path d="M12 5v.01M12 12v.01M12 19v.01" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent className="min-w-[100px]">
                    <DropdownMenuItem
                      className="text-red-500 text-sm cursor-pointer"
                      onClick={(e) => { e.stopPropagation(); setDeleteTarget(c.id); }}
                    >
                      删除
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            ))
          )}
        </div>
      </div>

      <div className="flex-1 overflow-hidden flex flex-col">
        <div className="md:hidden px-3 py-2 border-b flex items-center gap-2 bg-card/80 backdrop-blur-sm">
          <Button variant="ghost" size="sm" className="text-muted-foreground p-1.5" onClick={() => setSidebarOpen(true)}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </Button>
          <span className="text-sm font-medium text-muted-foreground">对话</span>
        </div>
        {children}
      </div>

      <Dialog open={newOpen} onOpenChange={setNewOpen}>
        <DialogContent className="sm:max-w-md rounded-2xl">
          <DialogHeader><DialogTitle>新建对话</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <Input placeholder="对话标题（可选）" value={title}
              onChange={(e) => setTitle(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && create()} />
            <Button onClick={create} className="w-full">创建</Button>
          </div>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        title="删除对话"
        message="确认删除此对话？所有消息将被永久删除。"
        confirmText="删除"
        danger
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
