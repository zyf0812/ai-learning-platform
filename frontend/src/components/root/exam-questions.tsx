"use client";

import { useState, useEffect } from "react";
import { api } from "@/lib/api";
import { Skeleton } from "@/components/ui/skeleton";

export default function ExamQuestions({ examId }: { examId: string }) {
  const [data, setData] = useState<any>(null);

  useEffect(() => {
    api.exams.get(examId).then(d => setData(d.exam));
  }, [examId]);

  if (!data) return <Skeleton className="h-20 w-full" />;

  return (
    <div className="space-y-1">
      {data.questions?.map((q: any, i: number) => (
        <div key={q.id} className="p-1.5 bg-gray-50 rounded text-xs">
          <p className="font-medium">{i + 1}. {q.content}</p>
          <p className="text-green-600">
            答案: {q.answer}
            {q.explanation && <span className="text-gray-400 ml-2">| {q.explanation}</span>}
          </p>
        </div>
      ))}
    </div>
  );
}
