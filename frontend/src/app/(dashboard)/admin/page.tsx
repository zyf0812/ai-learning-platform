"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/components/auth-context";
import { useRouter } from "next/navigation";

function WrongQuestionsSection({ wrongQuestions }: { wrongQuestions: any[] }) {
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const PAGE_SIZE = 10;
  const totalPages = Math.ceil((wrongQuestions?.length || 0) / PAGE_SIZE);
  const items = wrongQuestions.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  const toggle = (id: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  };

  return (
    <div>
      <p className="font-semibold mb-2">❌ 错题 ({wrongQuestions.length})</p>
      {items.map(w => {
        const isOpen = expanded.has(w.id);
        return (
          <div key={w.id} className="ml-2 mb-1 text-xs">
            <div className={`p-2 rounded cursor-pointer flex items-center justify-between ${isOpen ? "bg-red-50 dark:bg-red-950" : "bg-muted hover:bg-muted/80"}`}
                 onClick={() => toggle(w.id)}>
              <span className="truncate flex-1">{w.content?.slice(0, 50)}{(w.content?.length||0)>50?"...":""}</span>
              <span className="text-muted-foreground ml-2 shrink-0">{isOpen ? "▲" : "▼"}</span>
            </div>
            {isOpen && (
              <div className="p-2 bg-red-50 dark:bg-red-950 rounded-b text-xs space-y-1">
                <p className="font-medium">{w.content}</p>
                <p className="text-red-500">作答: {w.userAnswer || "未作答"}</p>
                <p className="text-green-600">正确: {w.answer}</p>
                {w.explanation && <p className="text-muted-foreground">💡 {w.explanation}</p>}
                {w.mastered && <Badge className="mt-1 bg-green-100 text-green-700">已掌握</Badge>}
              </div>
            )}
          </div>
        );
      })}
      {totalPages > 1 && (
        <div className="flex justify-center gap-2 mt-2">
          <Button size="sm" variant="outline" disabled={page===0} onClick={() => setPage(p=>p-1)}>上一页</Button>
          <span className="text-xs text-muted-foreground py-1">{page+1}/{totalPages}</span>
          <Button size="sm" variant="outline" disabled={page===totalPages-1} onClick={() => setPage(p=>p+1)}>下一页</Button>
        </div>
      )}
    </div>
  );
}

function SupervisionTab() {
  const [code, setCode] = useState("");
  const [users, setUsers] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  const token = localStorage.getItem("token");
  const headers = { Authorization: `Bearer ${token}` };

  const loadCode = async () => {
    const r = await fetch("/api/supervision/code", { headers });
    const d = await r.json();
    if (d.code) setCode(d.code);
  };
  const loadUsers = async () => {
    const r = await fetch("/api/supervision/users", { headers });
    setUsers((await r.json()).users || []);
  };
  useEffect(() => { loadCode(); loadUsers(); }, []);

  const approve = async (id: string) => { await fetch(`/api/supervision/approve/${id}`, { method: "POST", headers }); loadUsers(); };
  const reject = async (id: string) => { await fetch(`/api/supervision/reject/${id}`, { method: "POST", headers }); loadUsers(); };
  const remove = async (id: string) => { await fetch(`/api/supervision/remove/${id}`, { method: "POST", headers }); loadUsers(); };

  const pending = users.filter(u => u.status === "pending");
  const approved = users.filter(u => u.status === "approved");

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <p className="text-sm font-medium mb-2">你的监管码</p>
        <div className="flex items-center gap-2">
          <Input value={code} readOnly className="font-mono text-lg text-center" />
          <Button size="sm" variant="outline" onClick={() => { navigator.clipboard.writeText(code); setMsg("已复制"); }}>复制</Button>
        </div>
        <p className="text-xs text-muted-foreground mt-1">将此码发给用户，对方在"加入监管"中输入即可申请</p>
      </Card>

      {msg && <p className="text-green-600 text-sm">{msg}</p>}

      {pending.length > 0 && (
        <div>
          <p className="font-semibold mb-2">待审批 ({pending.length})</p>
          {pending.map(u => (
            <Card key={u.id} className="p-3 flex items-center justify-between mb-2">
              <span>{u.username}</span>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => approve(u.id)}>批准</Button>
                <Button size="sm" variant="destructive" className="text-white" onClick={() => reject(u.id)}>拒绝</Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <div>
        <p className="font-semibold mb-2">已监管用户 ({approved.length})</p>
        {approved.map(u => (
          <Card key={u.id} className="p-3 flex items-center justify-between mb-2">
            <span>{u.username}</span>
            <Button size="sm" variant="destructive" className="text-white" onClick={() => remove(u.id)}>移除</Button>
          </Card>
        ))}
      </div>
    </div>
  );
}

export default function AdminPanel() {
  const { user } = useAuth();
  const router = useRouter();
  const [users, setUsers] = useState<any[]>([]);
  const [tab, setTab] = useState<"users"|"notify"|"supervise">("supervise");
  const [selectedUser, setSelectedUser] = useState<any>(null);
  const [userExams, setUserExams] = useState<any[]>([]);
  const [userWrong, setUserWrong] = useState<any[]>([]);
  const [expandedExam, setExpandedExam] = useState<string | null>(null);
  const [examDetail, setExamDetail] = useState<any>(null);
  const [notifyTitle, setNotifyTitle] = useState("");
  const [notifyContent, setNotifyContent] = useState("");
  const [checkedUsers, setCheckedUsers] = useState<string[]>([]);
  const [msg, setMsg] = useState("");

  const token = typeof window !== "undefined" ? localStorage.getItem("token") : "";
  const headers = { Authorization: `Bearer ${token}` };

  const loadUsers = async () => {
    const r = await fetch("/api/admin/users", { headers });
    setUsers((await r.json()).users || []);
  };

  useEffect(() => {
    if (!user || (user.role !== "admin" && user.role !== "root")) { router.push("/"); return; }
    loadUsers();
  }, [user]);

  const viewUser = async (u: any) => {
    if (selectedUser?.id === u.id) { setSelectedUser(null); return; }
    setSelectedUser(u);
    setExpandedExam(null);
    const [e, w] = await Promise.all([
      fetch(`/api/admin/users/${u.id}/exams`, { headers }).then(r => r.json()),
      fetch(`/api/admin/users/${u.id}/wrong-questions`, { headers }).then(r => r.json()),
    ]);
    setUserExams(e.exams || []);
    setUserWrong(w.wrongQuestions || []);
  };

  const viewExamDetail = async (examId: string) => {
    if (expandedExam === examId) { setExpandedExam(null); setExamDetail(null); return; }
    const r = await fetch(`/api/exams/${examId}`, { headers });
    setExamDetail((await r.json()).exam);
    setExpandedExam(examId);
  };

  const sendNotify = async () => {
    if (!notifyTitle.trim() || checkedUsers.length === 0) return;
    await fetch("/api/notifications/send", {
      method: "POST", headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify({ title: notifyTitle, content: notifyContent, userIds: checkedUsers }),
    });
    setNotifyTitle(""); setNotifyContent(""); setCheckedUsers([]);
    setMsg(`已发送给 ${checkedUsers.length} 个用户`);
  };

  const typeLabels: Record<string, string> = { choice: "选择", fill: "填空", truefalse: "判断", shortanswer: "简答" };

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold">👥 管理员面板</h2>
      {msg && <div className="bg-green-50 text-green-700 p-2 rounded text-sm">{msg}</div>}

      <div className="flex gap-2 border-b pb-2">
        <Button variant={tab==="users"?"default":"outline"} size="sm" onClick={() => setTab("users")}>监管</Button>
        <Button variant={tab==="notify"?"default":"outline"} size="sm" onClick={() => setTab("notify")}>通知</Button>
        <Button variant={tab==="supervise"?"default":"outline"} size="sm" onClick={() => setTab("supervise")}>管理</Button>
      </div>

      {tab === "users" && (
        <div className="grid gap-3">
          {users.length === 0 && (
            <div className="text-center py-8 text-muted-foreground text-sm">
              <p>还没有监管的用户</p>
              <p className="mt-1">创建群组 → 让用户加入 → 即可在此查看他们的学习数据</p>
            </div>
          )}
          {users.map(u => (
            <Card key={u.id} className="p-4">
              <div className="flex items-center justify-between">
                <span className="font-medium">{u.username}</span>
                <Button size="sm" variant="outline" onClick={() => viewUser(u)}>{selectedUser?.id===u.id?"收起":"查看学习数据"}</Button>
              </div>
              {selectedUser?.id === u.id && (
                <div className="mt-3 border-t pt-3 space-y-4 text-sm">
                  {/* 考试记录 */}
                  <div>
                    <p className="font-semibold mb-2">📋 考试 ({userExams.length})</p>
                    {userExams.map(e => (
                      <div key={e.id} className="ml-2">
                        <div className="p-2 bg-muted rounded mb-1 cursor-pointer hover:bg-muted/80 flex justify-between"
                             onClick={() => viewExamDetail(e.id)}>
                          <span>{e.title}</span>
                          <span className="text-xs text-muted-foreground">{e.questionCount}题 {expandedExam===e.id?"▲":"▼"}</span>
                        </div>
                        {expandedExam === e.id && examDetail && (
                          <div className="ml-4 mb-2 space-y-2">
                            {examDetail.questions?.map((q: any, i: number) => (
                              <div key={q.id} className="p-2 border rounded text-xs">
                                <p className="font-medium">{i+1}. {q.content}</p>
                                <p className="text-green-600">答案: {q.answer}</p>
                                {q.explanation && <p className="text-muted-foreground">解析: {q.explanation}</p>}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>

                  {/* 错题 - 可折叠分页 */}
                  <WrongQuestionsSection wrongQuestions={userWrong} />
                </div>
              )}
            </Card>
          ))}
        </div>
      )}

      {tab === "notify" && (
        <Card className="p-4 space-y-3">
          <h3 className="font-semibold">发送通知</h3>
          <div className="max-h-40 overflow-y-auto border rounded p-2 space-y-1">
            {users.map(u => (
              <label key={u.id} className="flex items-center gap-2 text-sm cursor-pointer">
                <input type="checkbox" checked={checkedUsers.includes(u.id)}
                  onChange={() => setCheckedUsers(p => p.includes(u.id) ? p.filter(x => x!==u.id) : [...p, u.id])} />
                {u.username}
              </label>
            ))}
          </div>
          <Input placeholder="标题" value={notifyTitle} onChange={e => setNotifyTitle(e.target.value)} />
          <Input placeholder="内容" value={notifyContent} onChange={e => setNotifyContent(e.target.value)} />
          <Button onClick={sendNotify} disabled={checkedUsers.length===0}>发送 ({checkedUsers.length}人)</Button>
        </Card>
      )}

      {tab === "supervise" && <SupervisionTab />}
    </div>
  );
}
