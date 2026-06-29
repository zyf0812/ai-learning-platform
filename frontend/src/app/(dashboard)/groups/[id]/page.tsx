"use client";

import { useState, useEffect, useRef } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/components/auth-context";

export default function GroupDetailPage() {
  const params = useParams(); const router = useRouter();
  const { user: currentUser } = useAuth();
  const [group, setGroup] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState("");
  const [chatMsg, setChatMsg] = useState("");
  const [messages, setMessages] = useState<any[]>([]);
  const [tab, setTab] = useState<"chat"|"members"|"notify">("chat");
  const [notifyTitle, setNotifyTitle] = useState("");
  const [notifyContent, setNotifyContent] = useState("");
  const [checkedUsers, setCheckedUsers] = useState<string[]>([]);
  const bottomRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const token = typeof window !== "undefined" ? localStorage.getItem("token") : "";
  const hdrs: Record<string, string> = { Authorization: `Bearer ${token}` };

  const load = async () => {
    const r = await fetch(`/api/groups/${params.id}`, { headers: hdrs });
    const d = await r.json();
    if (d.error) { router.push("/groups"); return; }
    setGroup(d.group);
    setLoading(false);
  };

  const loadMessages = async () => {
    const r = await fetch(`/api/groups/${params.id}/chat`, { headers: hdrs });
    setMessages((await r.json()).messages || []);
  };

  useEffect(() => { load(); loadMessages(); }, [params.id]);
  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages]);

  const send = async () => {
    if (!chatMsg.trim()) return;
    await fetch(`/api/groups/${params.id}/chat/send`, { method: "POST", headers: { ...hdrs, "Content-Type": "application/json" }, body: JSON.stringify({ content: chatMsg }) });
    setChatMsg(""); loadMessages();
  };

  const upload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const fd = new FormData(); fd.append("file", file);
    await fetch(`/api/groups/${params.id}/chat/upload`, { method: "POST", headers: { Authorization: `Bearer ${token}` }, body: fd });
    loadMessages();
  };

  const sendNotify = async () => {
    if (!notifyTitle.trim() || checkedUsers.length === 0) return;
    await fetch(`/api/groups/${params.id}/chat/notify`, { method: "POST", headers: { ...hdrs, "Content-Type": "application/json" }, body: JSON.stringify({ title: notifyTitle, content: notifyContent, userIds: checkedUsers }) });
    setNotifyTitle(""); setNotifyContent(""); setCheckedUsers([]); setMsg("通知已发送");
  };

  const approve = async (mid: string) => { await fetch(`/api/groups/${params.id}/approve/${mid}`, { method: "POST", headers: hdrs }); load(); };
  const reject = async (mid: string) => { await fetch(`/api/groups/${params.id}/reject/${mid}`, { method: "POST", headers: hdrs }); load(); };
  const remove = async (mid: string) => { await fetch(`/api/groups/${params.id}/remove/${mid}`, { method: "POST", headers: hdrs }); load(); };
  const saveToDocs = async (fileName: string, fileUrl: string) => {
    await fetch(`/api/groups/${params.id}/chat/save-file`, { method: "POST", headers: { ...hdrs, "Content-Type": "application/json" }, body: JSON.stringify({ fileName, fileUrl }) });
    setMsg("已保存到文档库");
  };
  const disband = async () => {
    if (!confirm("解散群组将删除所有消息和文件，不可恢复！")) return;
    await fetch(`/api/groups/${params.id}`, { method: "DELETE", headers: hdrs });
    router.push("/groups");
  };

  if (loading) return <p className="text-muted-foreground">加载中...</p>;
  if (!group) return null;

  const members = group.members || [];
  const pending = members.filter((m: any) => m.status === "pending");
  const approved = members.filter((m: any) => m.status === "approved");

  return (
    <div className="space-y-4">
      <Button variant="outline" size="sm" onClick={() => router.push("/groups")}>← 返回</Button>
      {msg && <div className="bg-green-50 dark:bg-green-950 text-green-700 dark:text-green-300 p-2 rounded text-sm">{msg}</div>}

      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-foreground">{group.name}</h2>
          <p className="text-sm text-muted-foreground">群主: {group.adminName} · 码: <span className="font-mono text-primary">{group.code}</span></p>
        </div>
        {currentUser?.id === group.adminUserId && (
          <Button variant="destructive" className="text-white" size="sm" onClick={disband}>解散群组</Button>
        )}
      </div>

      {/* Tab切换 */}
      <div className="flex gap-2 border-b pb-2">
        {(["chat","members"] as const).map(t => (
          <Button key={t} variant={tab===t?"default":"outline"} size="sm" onClick={() => setTab(t)}>
            {t==="chat"?"💬 群聊":"👥 成员"}
          </Button>
        ))}
        {currentUser?.id === group.adminUserId && (
          <Button variant={tab==="notify"?"default":"outline"} size="sm" onClick={() => setTab("notify")}>📢 通知</Button>
        )}
      </div>

      {tab === "chat" && (
        <div className="space-y-3">
          <Card className="p-4 h-[50vh] overflow-y-auto space-y-3">
            {messages.length === 0 ? <p className="text-center text-muted-foreground py-8">暂无消息</p> :
              messages.slice().reverse().map((m: any) => (
                <div key={m.id} className={`flex gap-2 ${m.userId === (group.adminUserId) ? "" : ""}`}>
                  <div className="w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-xs font-bold text-primary shrink-0 mt-0.5">
                    {m.username?.charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground">{m.username} <span className="text-[10px]">{new Date(m.createdAt).toLocaleTimeString()}</span></p>
                    <div className="bg-muted rounded-xl rounded-tl-sm px-3 py-2 text-sm">
                      {m.fileName ? (
                        <div><a href={m.fileUrl} download className="text-primary hover:underline">📎 {m.fileName}</a>
                          <Button variant="link" size="sm" className="text-xs ml-2" onClick={() => saveToDocs(m.fileName, m.fileUrl)}>存到我的文档</Button>
                        </div>
                      ) : m.content}
                    </div>
                  </div>
                </div>
              ))}
            <div ref={bottomRef} />
          </Card>
          <div className="flex gap-2">
            {currentUser?.id === group.adminUserId && (
              <>
                <input type="file" ref={fileRef} className="hidden" onChange={upload} />
                <Button variant="outline" size="sm" onClick={() => fileRef.current?.click()}>📎</Button>
              </>
            )}
            <Input placeholder="输入消息..." value={chatMsg} onChange={e => setChatMsg(e.target.value)} onKeyDown={e => e.key==="Enter"&&send()} />
            <Button size="sm" onClick={send}>发送</Button>
          </div>
        </div>
      )}

      {tab === "members" && (
        <div className="space-y-4">
          {pending.length > 0 && (
            <div>
              <p className="font-semibold mb-2">待审批 ({pending.length})</p>
              <div className="grid gap-2">
                {pending.map((m: any) => (
                  <Card key={m.id} className="p-3 flex items-center justify-between">
                    <span className="text-sm">{m.username}</span>
                    {currentUser?.id === group.adminUserId && (
                      <div className="flex gap-2"><Button size="sm" onClick={() => approve(m.id)}>批准</Button><Button size="sm" variant="destructive" className="text-white" onClick={() => reject(m.id)}>拒绝</Button></div>
                    )}
                  </Card>
                ))}
              </div>
            </div>
          )}
          <div>
            <p className="font-semibold mb-2">成员 ({approved.length})</p>
            {approved.map((m: any) => (
              <Card key={m.id} className="p-3 flex items-center gap-3">
                <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-sm font-bold text-primary">{m.username?.charAt(0).toUpperCase()}</div>
                <span>{m.username}</span>
                <Badge variant="outline" className="ml-auto mr-2">已加入</Badge>
                {currentUser?.id === group.adminUserId && (
                  <Button size="sm" variant="destructive" className="text-white" onClick={() => remove(m.id)}>移除</Button>
                )}
              </Card>
            ))}
          </div>
        </div>
      )}

      {tab === "notify" && (
        <Card className="p-4 space-y-3">
          <h3 className="font-semibold">发送群通知</h3>
          <div className="max-h-40 overflow-y-auto border rounded p-2 space-y-1">
            {approved.map((m: any) => (
              <label key={m.userId} className="flex items-center gap-2 text-sm cursor-pointer">
                <input type="checkbox" checked={checkedUsers.includes(m.userId)} onChange={() => setCheckedUsers(p => p.includes(m.userId)?p.filter(x=>x!==m.userId):[...p,m.userId])} />
                {m.username}
              </label>
            ))}
          </div>
          <Input placeholder="标题" value={notifyTitle} onChange={e => setNotifyTitle(e.target.value)} />
          <Input placeholder="内容" value={notifyContent} onChange={e => setNotifyContent(e.target.value)} />
          <Button onClick={sendNotify} disabled={checkedUsers.length===0}>发送通知 ({checkedUsers.length}人)</Button>
        </Card>
      )}
    </div>
  );
}
