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
    const token = localStorage.getItem("token");
    const res = await fetch("/api/conversations", {
      headers: { Authorization: `Bearer ${token}` },
    });
    const text = await res.text();
    try { setConvs(JSON.parse(text).conversations || []); } catch {}
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const create = async () => {
    const token = localStorage.getItem("token");
    const res = await fetch("/api/conversations", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ title: title.trim() || "新对话" }),
    });
    const text = await res.text();
    try {
      const data = JSON.parse(text);
      if (!data.conversation?.id) return;
      setNewOpen(false);
      setTitle("");
      load();
      router.push(`/chat/${data.conversation.id}`);
    } catch { return; }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    const token = localStorage.getItem("token");
    await fetch(`/api/conversations/${deleteTarget}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
    });
    if (activeId === deleteTarget) router.push("/chat");
    setDeleteTarget(null);
    load();
  };

  return (
    <div className="flex -mx-4 -my-6 overflow-hidden" style={{ height: "calc(100vh - 4rem)" }}>
      {/* 移动端侧边栏遮罩 */}
      {sidebarOpen && (
        <div className="fixed inset-0 bg-black/30 z-10 md:hidden" onClick={() => setSidebarOpen(false)} />
      )}
      {/* 左侧对话列表 */}
      <div className={`${sidebarOpen ? 'fixed left-0 z-20 h-full' : 'hidden'} md:static md:flex w-64 border-r bg-card flex-col shrink-0`}>
        <div className="p-3 border-b flex items-center gap-2">
          <Button variant="ghost" size="sm" className="md:hidden" onClick={() => setSidebarOpen(false)}>✕</Button>
          <Button className="flex-1" size="sm" onClick={() => { setNewOpen(true); setSidebarOpen(false); }}>
            + 新建对话
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <p className="p-4 text-sm text-muted-foreground">加载中...</p>
          ) : convs.length === 0 ? (
            <p className="p-4 text-sm text-muted-foreground">暂无对话</p>
          ) : (
            convs.map((c) => (
              <div
                key={c.id}
                onClick={() => router.push(`/chat/${c.id}`)}
                className={`px-4 py-3 cursor-pointer hover:bg-muted border-b border-border text-sm group flex items-center justify-between ${
                  activeId === c.id ? "bg-primary/10 border-l-2 border-l-primary" : ""
                }`}
              >
                <div className="flex-1 min-w-0">
                  <p className="truncate font-medium">{c.title}</p>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {new Date(c.createdAt).toLocaleDateString("zh-CN")}
                  </p>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger className="opacity-0 group-hover:opacity-100 p-1 hover:bg-muted rounded" onClick={(e) => e.stopPropagation()}>
                    <svg width="14" height="14" viewBox="0 0 14 14"><circle cx="7" cy="2" r="1.5"/><circle cx="7" cy="7" r="1.5"/><circle cx="7" cy="12" r="1.5"/></svg>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent>
                    <DropdownMenuItem className="text-red-500" onClick={(e) => { e.stopPropagation(); setDeleteTarget(c.id); }}>
                      删除
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            ))
          )}
        </div>
      </div>

      {/* 右侧内容区 */}
      <div className="flex-1 overflow-hidden">
        {/* 移动端：显示侧边栏按钮 */}
        <div className="md:hidden px-3 py-2 border-b flex items-center gap-2 sticky top-0 bg-card z-10">
          <Button variant="ghost" size="sm" onClick={() => setSidebarOpen(true)}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" /></svg>
          </Button>
          <span className="text-sm font-medium text-muted-foreground">对话历史</span>
        </div>
        {children}
      </div>

      <Dialog open={newOpen} onOpenChange={setNewOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>新建对话</DialogTitle></DialogHeader>
          <Input placeholder="对话标题（可选）" value={title}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && create()} />
          <Button onClick={create}>创建</Button>
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
