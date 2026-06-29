"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

const TYPE_LABELS: Record<string, string> = {
  choice: "选择题", fill: "填空题", truefalse: "判断题", shortanswer: "简答题",
};

const TYPE_COLORS: Record<string, string> = {
  choice: "bg-blue-100 text-blue-700", fill: "bg-green-100 text-green-700",
  truefalse: "bg-orange-100 text-orange-700", shortanswer: "bg-purple-100 text-purple-700",
};

export default function ExamDetailPage() {
  const params = useParams(); const router = useRouter();
  const [exam, setExam] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [hasAttempt, setHasAttempt] = useState(false);

  useEffect(() => {
    const examId = params.id as string;
    Promise.all([
      api.exams.get(examId),
      api.attempts.list({ examId }).catch(() => ({ attempts: [] }))
    ]).then(([examData, attemptData]: any[]) => {
        setExam(examData.exam);
        const hasResult = !!(typeof window !== "undefined" && sessionStorage.getItem("examResult_" + examId))
                       || (attemptData.attempts && attemptData.attempts.length > 0);
        setHasAttempt(hasResult);
        setLoading(false);
      })
      .catch(() => router.push("/exams")).finally(() => setLoading(false));
  }, [params.id, router]);

  if (loading) return <div className="space-y-2">{Array.from({length:3}).map((_,i)=><Skeleton key={i} className="h-20 w-full" />)}</div>;
  if (!exam) return null;

  return (
    <div className="max-w-3xl mx-auto space-y-6">
      <div>
        <h2 className="text-xl font-semibold">{exam.title}</h2>
        <p className="text-sm text-muted-foreground">
          共 {exam.questions?.length || 0} 题 · {exam.questionTypes}
          {hasAttempt && <span className="text-green-600 ml-2">✅ 已完成</span>}
        </p>
      </div>
      {hasAttempt ? (
        <Button className="w-full" size="lg" onClick={() => router.push(`/exams/${exam.id}/result`)}>
          查看结果
        </Button>
      ) : (
        <Button className="w-full" size="lg" onClick={() => router.push(`/exams/${exam.id}/take`)}>
          开始考试
        </Button>
      )}
      <div className="space-y-3">
        <h3 className="text-sm font-medium text-muted-foreground">考题预览</h3>
        {exam.questions?.map((q: any, i: number) => (
          <Card key={q.id} className="p-4 flex items-start gap-3">
            <span className="text-sm font-bold text-muted-foreground mt-0.5">{i + 1}.</span>
            <div className="flex-1">
              <Badge variant="outline" className={`text-xs mb-1 ${TYPE_COLORS[q.type] || ""}`}>
                {TYPE_LABELS[q.type] || q.type}
              </Badge>
              <p className="text-sm">{q.content}</p>
            </div>
          </Card>
        ))}
      </div>
      <Button variant="outline" onClick={() => router.push("/exams")}>返回列表</Button>
    </div>
  );
}
