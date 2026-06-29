"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/components/auth-context";
import { api } from "@/lib/api";
import { Skeleton } from "@/components/ui/skeleton";
import GroupChat from "@/components/groups/group-chat";
import GroupMembers from "@/components/groups/group-members";
import GroupNotify from "@/components/groups/group-notify";

export default function GroupDetailPage() {
  const params = useParams();
  const router = useRouter();
  const { user: currentUser } = useAuth();
  const [group, setGroup] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [messages, setMessages] = useState<any[]>([]);
  const [tab, setTab] = useState<"chat" | "members" | "notify">("chat");
  const [msg, setMsg] = useState("");

  const load = async () => {
    try {
      const d = await api.groups.get(params.id as string);
      setGroup(d.group);
    } catch {
      router.push("/groups");
      return;
    } finally {
      setLoading(false);
    }
  };

  const loadMessages = async () => {
    const d = await api.groups.chat.list(params.id as string);
    setMessages(d.messages || []);
  };

  useEffect(() => { load(); loadMessages(); }, [params.id]);

  const handleUpload = async (file: File) => {
    const fd = new FormData();
    fd.append("file", file);
    await api.groups.chat.upload(params.id as string, fd);
    loadMessages();
  };

  const handleSaveFile = async (fileName: string, fileUrl: string) => {
    await api.groups.chat.saveFile(params.id as string, { fileName, fileUrl });
    setMsg("已保存到文档库");
  };

  const handleApprove = async (memberId: string) => {
    await api.groups.approve(params.id as string, memberId);
    load();
  };

  const handleReject = async (memberId: string) => {
    await api.groups.reject(params.id as string, memberId);
    load();
  };

  const handleRemove = async (memberId: string) => {
    await api.groups.remove(params.id as string, memberId);
    load();
  };

  const disband = async () => {
    if (!confirm("解散群组将删除所有消息和文件，不可恢复！")) return;
    await api.groups.delete(params.id as string);
    router.push("/groups");
  };

  if (loading) return (
    <div className="space-y-4">
      <Skeleton className="h-8 w-24" />
      <Skeleton className="h-6 w-48" />
      <Skeleton className="h-[50vh] w-full" />
    </div>
  );

  if (!group) return null;

  const members = group.members || [];
  const isAdmin = currentUser?.id === group.adminUserId;

  return (
    <div className="space-y-4">
      <Button variant="outline" size="sm" onClick={() => router.push("/groups")}>← 返回</Button>
      {msg && <div className="bg-green-50 dark:bg-green-950 text-green-700 dark:text-green-300 p-2 rounded text-sm">{msg}</div>}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-foreground">{group.name}</h2>
          <p className="text-sm text-muted-foreground">
            群主: {group.adminName} · 码: <span className="font-mono text-primary">{group.code}</span>
          </p>
        </div>
        {isAdmin && (
          <Button variant="destructive" className="text-white" size="sm" onClick={disband}>解散群组</Button>
        )}
      </div>

      <div className="flex gap-2 border-b pb-2">
        {(["chat", "members"] as const).map(t => (
          <Button key={t} variant={tab === t ? "default" : "outline"} size="sm" onClick={() => setTab(t)}>
            {t === "chat" ? "💬 群聊" : "👥 成员"}
          </Button>
        ))}
        {isAdmin && (
          <Button variant={tab === "notify" ? "default" : "outline"} size="sm" onClick={() => setTab("notify")}>📢 通知</Button>
        )}
      </div>

      {tab === "chat" && (
        <GroupChat
          groupId={params.id as string}
          adminUserId={group.adminUserId}
          messages={messages}
          onSend={loadMessages}
          onUpload={handleUpload}
          onSaveFile={handleSaveFile}
        />
      )}

      {tab === "members" && (
        <GroupMembers
          members={members}
          adminUserId={group.adminUserId}
          onApprove={handleApprove}
          onReject={handleReject}
          onRemove={handleRemove}
        />
      )}

      {tab === "notify" && (
        <GroupNotify
          groupId={params.id as string}
          members={members}
          onSent={loadMessages}
        />
      )}
    </div>
  );
}
