"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export default function TakeExamPage() {
  const params = useParams(); const router = useRouter();
  const [exam, setExam] = useState<any>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem("token");
    fetch(`/api/exams/${params.id}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json()).then(d => {
        setExam(d.exam);
        // 已提交过 → 跳结果页
        const hasResult = typeof window !== "undefined" && sessionStorage.getItem("examResult_" + d.exam.id);
        if (hasResult) router.push(`/exams/${d.exam.id}/result`);
      })
      .catch(() => router.push("/exams")).finally(() => setLoading(false));
  }, [params.id, router]);

  if (loading) return <p className="text-muted-foreground">加载中...</p>;
  if (!exam) return null;

  const submit = async () => {
    setSubmitting(true);
    try {
      const token = localStorage.getItem("token");
      const res = await fetch(`/api/exams/${exam.id}/submit`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
        body: JSON.stringify({ answers }),
      });
      const data = await res.json();
      if (data.error) throw new Error(data.error);
      // 把结果存 localStorage 给结果页用
      sessionStorage.setItem("examResult_" + exam.id, JSON.stringify(data.attempt));
      router.push(`/exams/${exam.id}/result`);
    } catch (err: any) {
      setError(err.message);
    } finally { setSubmitting(false); }
  };

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <h2 className="text-xl font-semibold">{exam.title} — 答题</h2>
      <div className="space-y-4">
        {exam.questions.map((q: any, i: number) => (
          <Card key={q.id} className="p-4">
            <p className="font-medium mb-3">{i + 1}. {q.content}</p>
            {q.type === "choice" && (
              <div className="space-y-2">
                {JSON.parse(q.options).map((opt: string, j: number) => {
                  const parts = opt.split("|");
                  const label = parts[0]?.trim() || String.fromCharCode(65 + j);
                  const text = parts[1]?.trim() || opt;
                  return (
                  <label key={j} className="flex items-center gap-2 cursor-pointer p-2 rounded hover:bg-muted">
                    <input type="radio" name={`q-${q.id}`} checked={answers[q.id] === label}
                      onChange={() => setAnswers({...answers, [q.id]: label})} />
                    <span className="text-sm">{label}. {text}</span>
                  </label>
                  );
                })}
              </div>
            )}
            {q.type === "truefalse" && (
              <div className="flex gap-4">
                {["正确","错误"].map(v => (
                  <label key={v} className="flex items-center gap-2 cursor-pointer">
                    <input type="radio" name={`q-${q.id}`} checked={answers[q.id] === v}
                      onChange={() => setAnswers({...answers, [q.id]: v})} />
                    <span>{v}</span>
                  </label>
                ))}
              </div>
            )}
            {(q.type === "fill" || q.type === "shortanswer") && (
              <Input placeholder="输入答案" value={answers[q.id] || ""}
                onChange={e => setAnswers({...answers, [q.id]: e.target.value})} />
            )}
          </Card>
        ))}
      </div>
      {error && (
        <div className="bg-red-50 border border-red-200 text-red-600 p-3 rounded-lg text-sm">{error}</div>
      )}
      <Button className="w-full" size="lg" onClick={submit} disabled={submitting}>
        {submitting ? "提交中..." : "提交答卷"}
      </Button>
    </div>
  );
}
