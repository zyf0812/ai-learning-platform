"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

export default function WrongQuestionsSection({ wrongQuestions }: { wrongQuestions: any[] }) {
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
      <p className="font-semibold mb-2">错题 ({wrongQuestions.length})</p>
      {items.map(w => {
        const isOpen = expanded.has(w.id);
        return (
          <div key={w.id} className="ml-2 mb-1 text-xs">
            <div
              className={`p-2 rounded cursor-pointer flex items-center justify-between ${isOpen ? "bg-red-50 dark:bg-red-950" : "bg-muted hover:bg-muted/80"}`}
              onClick={() => toggle(w.id)}
            >
              <span className="truncate flex-1">{w.content?.slice(0, 50)}{(w.content?.length || 0) > 50 ? "..." : ""}</span>
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
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(p => p - 1)}>上一页</Button>
          <span className="text-xs text-muted-foreground py-1">{page + 1}/{totalPages}</span>
          <Button size="sm" variant="outline" disabled={page === totalPages - 1} onClick={() => setPage(p => p + 1)}>下一页</Button>
        </div>
      )}
    </div>
  );
}
