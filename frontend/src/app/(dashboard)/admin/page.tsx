"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/components/auth-context";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { Skeleton } from "@/components/ui/skeleton";
import WrongQuestionsSection from "@/components/admin/wrong-questions-section";
import SupervisionTab from "@/components/admin/supervision-tab";

export default function AdminPanel() {
  const { user } = useAuth();
  const router = useRouter();
  const [users, setUsers] = useState<any[]>([]);
  const [tab, setTab] = useState<"users" | "notify" | "supervise">("supervise");
  const [selectedUser, setSelectedUser] = useState<any>(null);
  const [userExams, setUserExams] = useState<any[]>([]);
  const [userWrong, setUserWrong] = useState<any[]>([]);
  const [expandedExam, setExpandedExam] = useState<string | null>(null);
  const [examDetail, setExamDetail] = useState<any>(null);
  const [notifyTitle, setNotifyTitle] = useState("");
  const [notifyContent, setNotifyContent] = useState("");
  const [checkedUsers, setCheckedUsers] = useState<string[]>([]);
  const [msg, setMsg] = useState("");
  const [loading, setLoading] = useState(true);

  const loadUsers = async () => {
    const d = await api.admin.users();
    setUsers(d.users || []);
    setLoading(false);
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
      api.admin.userExams(u.id),
      api.admin.userWrongQuestions(u.id),
    ]);
    setUserExams(e.exams || []);
    setUserWrong(w.wrongQuestions || []);
  };

  const viewExamDetail = async (examId: string) => {
    if (expandedExam === examId) { setExpandedExam(null); setExamDetail(null); return; }
    const d = await api.exams.get(examId);
    setExamDetail(d.exam);
    setExpandedExam(examId);
  };

  const sendNotify = async () => {
    if (!notifyTitle.trim() || checkedUsers.length === 0) return;
    await api.notifications.send({ title: notifyTitle, content: notifyContent, userIds: checkedUsers });
    setNotifyTitle("");
    setNotifyContent("");
    setCheckedUsers([]);
    setMsg(`已发送给 ${checkedUsers.length} 个用户`);
  };

  if (loading) return (
    <div className="space-y-4">
      <Skeleton className="h-8 w-48" />
      <div className="space-y-3">
        {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-24 w-full" />)}
      </div>
    </div>
  );

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold">管理员面板</h2>
      {msg && <div className="bg-green-50 text-green-700 p-2 rounded text-sm">{msg}</div>}

      <div className="flex gap-2 border-b pb-2">
        <Button variant={tab === "users" ? "default" : "outline"} size="sm" onClick={() => setTab("users")}>监管</Button>
        <Button variant={tab === "notify" ? "default" : "outline"} size="sm" onClick={() => setTab("notify")}>通知</Button>
        <Button variant={tab === "supervise" ? "default" : "outline"} size="sm" onClick={() => setTab("supervise")}>管理</Button>
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
                <Button size="sm" variant="outline" onClick={() => viewUser(u)}>
                  {selectedUser?.id === u.id ? "收起" : "查看学习数据"}
                </Button>
              </div>
              {selectedUser?.id === u.id && (
                <div className="mt-3 border-t pt-3 space-y-4 text-sm">
                  <div>
                    <p className="font-semibold mb-2">考试 ({userExams.length})</p>
                    {userExams.map(e => (
                      <div key={e.id} className="ml-2">
                        <div
                          className="p-2 bg-muted rounded mb-1 cursor-pointer hover:bg-muted/80 flex justify-between"
                          onClick={() => viewExamDetail(e.id)}
                        >
                          <span>{e.title}</span>
                          <span className="text-xs text-muted-foreground">{e.questionCount}题 {expandedExam === e.id ? "▲" : "▼"}</span>
                        </div>
                        {expandedExam === e.id && examDetail && (
                          <div className="ml-4 mb-2 space-y-2">
                            {examDetail.questions?.map((q: any, i: number) => (
                              <div key={q.id} className="p-2 border rounded text-xs">
                                <p className="font-medium">{i + 1}. {q.content}</p>
                                <p className="text-green-600">答案: {q.answer}</p>
                                {q.explanation && <p className="text-muted-foreground">解析: {q.explanation}</p>}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
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
                <input
                  type="checkbox"
                  checked={checkedUsers.includes(u.id)}
                  onChange={() => setCheckedUsers(p => p.includes(u.id) ? p.filter(x => x !== u.id) : [...p, u.id])}
                />
                {u.username}
              </label>
            ))}
          </div>
          <Input placeholder="标题" value={notifyTitle} onChange={e => setNotifyTitle(e.target.value)} />
          <Input placeholder="内容" value={notifyContent} onChange={e => setNotifyContent(e.target.value)} />
          <Button onClick={sendNotify} disabled={checkedUsers.length === 0}>发送 ({checkedUsers.length}人)</Button>
        </Card>
      )}

      {tab === "supervise" && <SupervisionTab />}
    </div>
  );
}
