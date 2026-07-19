"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";
import ReactMarkdown from "react-markdown";

const TYPE_LABELS: Record<string, string> = {
  choice: "选择题", fill: "填空题", truefalse: "判断题", shortanswer: "简答题",
};

export default function ExamResultPage() {
  const params = useParams();
  const router = useRouter();
  const [result, setResult] = useState<any>(null);
  const [exam, setExam] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const examId = params.id as string;

    // 从 sessionStorage 读提交结果
    const cached = sessionStorage.getItem("examResult_" + examId);
    const attempt = cached ? JSON.parse(cached) : null;

    // 同时获取考试详情
    api.exams.get(examId).then((d: any) => {
        setExam(d.exam);
        setResult({ attempt, exam: d.exam });
        setLoading(false);
      })
      .catch(() => { router.push("/exams"); setLoading(false); });
  }, [params.id, router]);

  if (loading) return <div className="space-y-2">{Array.from({length:3}).map((_,i)=><Skeleton key={i} className="h-20 w-full" />)}</div>;
  if (!result) return null;

  const { attempt } = result;
  const graded = attempt?.answers || {};
  const score = attempt?.score || 0;
  const total = attempt?.total || exam?.questions?.length || 0;

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <Card className="p-6 text-center">
        <p className="text-4xl font-bold mb-2">{score}/{total}</p>
        <p className="text-muted-foreground">
          {score === total ? "🎉 全部正确！" :
           score >= total * 0.8 ? "👍 表现不错！" :
           score >= total * 0.6 ? "📖 还需努力" : "💪 继续加油"}
        </p>
      </Card>

      <div className="space-y-4">
        {exam?.questions?.map((q: any, i: number) => {
          const g = graded[q.id] || {};
          const isCorrect = g.correct;
          const isFuzzy = g.isFuzzyMatch;
          // 三种状态：完全正确(绿) / 答案相近(黄) / 错误(红)
          const borderColor = isCorrect ? (isFuzzy ? "border-l-yellow-500" : "border-l-green-500") : "border-l-red-500";
          const iconColor = isCorrect ? (isFuzzy ? "text-yellow-600" : "text-green-600") : "text-red-500";
          const icon = isCorrect ? (isFuzzy ? "🟡" : "✅") : "❌";

          return (
            <Card key={q.id} className={`p-4 border-l-4 ${borderColor}`}>
              <div className="flex items-start gap-2 mb-2">
                <Badge variant="outline" className="text-xs">{TYPE_LABELS[q.type] || q.type}</Badge>
                <span className={`text-xs font-bold ${iconColor}`}>
                  {icon}
                </span>
                {isFuzzy && (
                  <Badge variant="outline" className="text-xs text-yellow-700 bg-yellow-50 border-yellow-300">
                    答案相近 · 命中 {Math.round((g.hitRatio || 0) * 100)}%
                  </Badge>
                )}
                <span className="font-medium">{i + 1}. {q.content}</span>
              </div>

              {q.type === "choice" && (() => {
                const options = JSON.parse(q.options || "[]");
                return (
                  <div className="ml-8 space-y-1.5 mb-2">
                    {options.map((opt: string, j: number) => {
                      const parts = opt.split("|");
                      const label = parts[0]?.trim() || "";
                      const text = parts[1]?.trim() || opt;
                      const optExp = parts[2]?.trim() || "";
                      const isAnswer = label === q.answer;
                      const wasChosen = label === (g.userAnswer || "");

                      return (
                        <div key={j} className={`text-sm p-1.5 rounded ${
                          isAnswer ? "bg-green-50 border border-green-200" :
                          wasChosen && !isCorrect ? "bg-red-50 border border-red-200" : ""
                        }`}>
                          <span className={`font-medium ${isAnswer ? "text-green-700" : wasChosen ? "text-red-500" : "text-foreground"}`}>
                            {label}. {text}
                            {isAnswer && " ← 正确答案"}
                            {wasChosen && !isAnswer && " ← 你的选择"}
                          </span>
                          {optExp && (
                            <p className="text-xs text-muted-foreground mt-0.5 ml-4">💡 {optExp}</p>
                          )}
                        </div>
                      );
                    })}
                  </div>
                );
              })()}

              {(q.type === "fill" || q.type === "truefalse" || q.type === "shortanswer") && (
                <div className="ml-8 text-sm space-y-1 mb-2">
                  <p>你的答案：<span className={isCorrect ? "text-green-600" : "text-red-500"}>{g.userAnswer || "未作答"}</span></p>
                  {!isCorrect && <p className="text-green-600">正确答案：{g.correctAnswer || q.answer}</p>}
                </div>
              )}

              {q.explanation && (
                <div className="ml-8 text-sm text-foreground bg-muted p-2 rounded">
                  <span className="font-medium">📖 解析：</span>
                  <ReactMarkdown>{q.explanation}</ReactMarkdown>
                </div>
              )}
            </Card>
          );
        })}
      </div>

      <div className="flex gap-4">
        <Button variant="outline" onClick={() => router.push("/exams")}>返回列表</Button>
      </div>
    </div>
  );
}
