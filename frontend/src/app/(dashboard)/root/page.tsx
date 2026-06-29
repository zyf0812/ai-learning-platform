"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/components/auth-context";
import { useRouter } from "next/navigation";

// 自动加载考试题目
function ExamQuestions({ examId, token }: { examId: string; token: string }) {
  const [data, setData] = useState<any>(null);
  useEffect(() => {
    fetch(`/api/exams/${examId}`, { headers: { Authorization: token } })
      .then(r => r.json()).then(d => setData(d.exam));
  }, [examId, token]);
  if (!data) return <p className="text-xs text-gray-400">加载中...</p>;
  return (
    <div className="space-y-1">
      {data.questions?.map((q: any, i: number) => (
        <div key={q.id} className="p-1.5 bg-gray-50 rounded text-xs">
          <p className="font-medium">{i+1}. {q.content}</p>
          <p className="text-green-600">答案: {q.answer}{q.explanation && <span className="text-gray-400 ml-2">| {q.explanation}</span>}</p>
        </div>
      ))}
    </div>
  );
}

// 自动加载对话消息
function ChatMessages({ convId, token }: { convId: string; token: string }) {
  const [msgs, setMsgs] = useState<any[]>([]);
  useEffect(() => {
    fetch(`/api/conversations/${convId}`, { headers: { Authorization: token } })
      .then(r => r.json()).then(d => setMsgs(d.conversation?.messages || []));
  }, [convId, token]);
  return (
    <div className="space-y-1 max-h-40 overflow-y-auto">
      {msgs.map(m => (
        <div key={m.id} className={`p-1.5 rounded text-xs ${m.role==="user"?"bg-blue-50":"bg-gray-100"}`}>
          <span className="font-medium">{m.role==="user"?"用户":"AI"}: </span>
          {m.content}
        </div>
      ))}
    </div>
  );
}

export default function RootPanel() {
  const { user } = useAuth();
  const router = useRouter();
  const [tab, setTab] = useState<"admins"|"users"|"broadcast">("admins");
  const [pendingAdmins, setPendingAdmins] = useState<any[]>([]);
  const [allUsers, setAllUsers] = useState<any[]>([]);
  const [broadcastTitle, setBroadcastTitle] = useState("");
  const [broadcastContent, setBroadcastContent] = useState("");
  const [selectedUser, setSelectedUser] = useState<any>(null);
  const [userDocs, setUserDocs] = useState<any[]>([]);
  const [userExams, setUserExams] = useState<any[]>([]);
  const [userChats, setUserChats] = useState<any[]>([]);
  const [userStats, setUserStats] = useState<any>(null);
  const [msg, setMsg] = useState("");

  const token = typeof window !== "undefined" ? localStorage.getItem("token") : "";
  const hdrs = { Authorization: `Bearer ${token}` };

  const loadPending = async () => {
    const r = await fetch("/api/root/admins/pending", { headers: hdrs });
    setPendingAdmins((await r.json()).users || []);
  };
  const loadUsers = async () => {
    const r = await fetch("/api/root/users", { headers: hdrs });
    setAllUsers((await r.json()).users || []);
  };

  useEffect(() => {
    if (user?.role !== "root") { router.push("/"); return; }
    loadPending(); loadUsers();
  }, [user]);

  const approve = async (id: string) => {
    await fetch(`/api/root/admins/${id}/approve`, { method: "POST", headers: hdrs });
    loadPending(); loadUsers(); setMsg("已批准");
  };
  const reject = async (id: string) => {
    await fetch(`/api/root/admins/${id}/reject`, { method: "POST", headers: hdrs });
    loadPending(); loadUsers(); setMsg("已拒绝");
  };
  const deleteUser = async (id: string) => {
    if (!confirm("确认删除此用户？")) return;
    await fetch(`/api/root/users/${id}`, { method: "DELETE", headers: hdrs });
    loadUsers(); setMsg("已删除");
  };

  const viewUser = async (u: any) => {
    if (selectedUser?.id === u.id) { setSelectedUser(null); return; }
    setSelectedUser(u);
    // 并行加载所有数据
    const token = hdrs.Authorization;
    const [d, e, c, s] = await Promise.all([
      fetch(`/api/root/users/${u.id}/documents`, { headers: hdrs }).then(r => r.json()),
      fetch(`/api/root/users/${u.id}/exams`, { headers: hdrs }).then(r => r.json()),
      fetch(`/api/root/users/${u.id}/chats`, { headers: hdrs }).then(r => r.json()),
      fetch(`/api/stats?userId=${u.id}`, { headers: hdrs }).then(r => r.json()).catch(() => ({ stats: null })),
    ]);
    setUserDocs(d.documents || []);
    setUserExams(e.exams || []);
    setUserChats(c.conversations || []);
    setUserStats(s.stats);
  };

  const broadcast = async () => {
    if (!broadcastTitle.trim()) return;
    await fetch("/api/notifications/broadcast", {
      method: "POST", headers: { ...hdrs, "Content-Type": "application/json" },
      body: JSON.stringify({ title: broadcastTitle, content: broadcastContent }),
    });
    setBroadcastTitle(""); setBroadcastContent(""); setMsg("已广播");
  };

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold">🛡️ Root 控制台</h2>
      {msg && <div className="bg-green-50 text-green-700 p-2 rounded text-sm">{msg}</div>}

      <div className="flex gap-2 border-b pb-2">
        {(["admins","users","broadcast"] as const).map(t => (
          <Button key={t} variant={tab===t ? "default" : "outline"} size="sm" onClick={() => setTab(t)}>
            {t==="admins"?"待审批":t==="users"?"用户管理":"全局广播"}
          </Button>
        ))}
      </div>

      {tab === "admins" && (
        <div className="space-y-3">
          {pendingAdmins.length === 0 ? <p className="text-gray-400 text-sm">无待审批管理员</p> :
            pendingAdmins.map(u => (
              <Card key={u.id} className="p-4 flex items-center justify-between">
                <div><span className="font-medium">{u.username}</span><span className="text-xs text-gray-400 ml-2">{u.id}</span></div>
                <div className="flex gap-2">
                  <Button size="sm" onClick={() => approve(u.id)}>批准</Button>
                  <Button size="sm" variant="destructive" className="text-white" onClick={() => reject(u.id)}>拒绝</Button>
                </div>
              </Card>
            ))
          }
        </div>
      )}

      {tab === "users" && (
        <div className="space-y-3">
          {allUsers.map(u => (
            <Card key={u.id} className="p-4">
              <div className="flex items-center justify-between mb-2">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{u.username}</span>
                  <Badge variant={u.role==="root"?"default":u.role==="admin"?"secondary":"outline"} className="text-xs">{u.role}</Badge>
                  <span className="text-xs text-gray-400">{u.status}</span>
                </div>
                <div className="flex gap-2">
                  <Button size="sm" variant="outline" onClick={() => viewUser(u)}>{selectedUser?.id===u.id?"收起":"查看"}</Button>
                  {u.role !== "root" && <Button size="sm" variant="destructive" className="text-white" onClick={() => deleteUser(u.id)}>删除</Button>}
                </div>
              </div>

              {selectedUser?.id === u.id && (
                <div className="mt-3 border-t pt-3 space-y-5 text-sm">
                  {/* 统计条 */}
                  {userStats && (
                    <div className="grid grid-cols-5 gap-2 text-center">
                      <div className="bg-blue-50 rounded p-2"><p className="font-bold text-blue-700">{userStats.documentCount}</p><p className="text-[10px] text-gray-400">文档</p></div>
                      <div className="bg-violet-50 rounded p-2"><p className="font-bold text-violet-700">{userStats.examCount}</p><p className="text-[10px] text-gray-400">考试</p></div>
                      <div className="bg-emerald-50 rounded p-2"><p className="font-bold text-emerald-700">{userStats.attemptCount}</p><p className="text-[10px] text-gray-400">答题</p></div>
                      <div className="bg-amber-50 rounded p-2"><p className="font-bold text-amber-700">{userStats.flashcardCount}</p><p className="text-[10px] text-gray-400">闪卡</p></div>
                      <div className="bg-rose-50 rounded p-2"><p className="font-bold text-rose-700">{userStats.avgScore}%</p><p className="text-[10px] text-gray-400">正确率</p></div>
                    </div>
                  )}

                  {/* 文档 - 全量 */}
                  <details open>
                    <summary className="font-semibold cursor-pointer">📄 文档 ({userDocs.length})</summary>
                    <div className="mt-2 space-y-2 max-h-64 overflow-y-auto">
                      {userDocs.map(d => (
                        <div key={d.id} className="p-2 bg-gray-50 rounded">
                          <p className="font-medium">{d.title} <span className="text-xs text-gray-400">({d.fileType})</span></p>
                          <p className="text-xs text-gray-500 mt-0.5 whitespace-pre-wrap max-h-48 overflow-y-auto">{d.content}</p>
                        </div>
                      ))}
                    </div>
                  </details>

                  {/* 考试 - 全部展开 */}
                  <details open>
                    <summary className="font-semibold cursor-pointer">📋 考试 ({userExams.length})</summary>
                    <div className="mt-2 space-y-3 max-h-96 overflow-y-auto">
                      {userExams.map(e => (
                        <div key={e.id} className="border rounded p-3">
                          <p className="font-medium mb-2">{e.title} ({e.questionCount}题)</p>
                          <ExamQuestions examId={e.id} token={hdrs.Authorization} />
                        </div>
                      ))}
                    </div>
                  </details>

                  {/* 对话 - 全量消息 */}
                  <details open>
                    <summary className="font-semibold cursor-pointer">💬 对话 ({userChats.length})</summary>
                    <div className="mt-2 space-y-3 max-h-96 overflow-y-auto">
                      {userChats.map(c => (
                        <div key={c.id} className="border rounded p-3">
                          <p className="font-medium mb-1">{c.title}</p>
                          <ChatMessages convId={c.id} token={hdrs.Authorization} />
                        </div>
                      ))}
                    </div>
                  </details>
                </div>
              )}
            </Card>
          ))}
        </div>
      )}

      {tab === "broadcast" && (
        <Card className="p-4 space-y-3">
          <h3 className="font-semibold">全局广播</h3>
          <Input placeholder="标题" value={broadcastTitle} onChange={e => setBroadcastTitle(e.target.value)} />
          <Input placeholder="内容" value={broadcastContent} onChange={e => setBroadcastContent(e.target.value)} />
          <Button onClick={broadcast}>📢 立即广播</Button>
        </Card>
      )}
    </div>
  );
}
