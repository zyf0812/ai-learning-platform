"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

const TYPE_LABELS: Record<string, string> = {
  choice: "选择题", fill: "填空题", truefalse: "判断题", shortanswer: "简答题",
};

export default function WrongQuestionsPage() {
  const [items, setItems] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    const token = localStorage.getItem("token");
    const res = await fetch("/api/wrong-questions", { headers: { Authorization: `Bearer ${token}` } });
    setItems((await res.json()).wrongQuestions || []);
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const markMastered = async (id: string) => {
    const token = localStorage.getItem("token");
    await fetch("/api/wrong-questions", {
      method: "PATCH",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ id, mastered: true }),
    });
    load();
  };

  if (loading) return <p className="text-gray-400">加载中...</p>;

  return (
    <div className="space-y-4">
      <h2 className="text-xl font-semibold">错题本</h2>
      {items.length === 0 ? (
        <Card className="p-12 text-center text-gray-400">
          <p className="text-lg mb-2">暂无错题</p>
          <p>继续加油！做错的题目会自动收集到这里</p>
        </Card>
      ) : (
        <div className="grid gap-3">
          {items.map((item) => (
            <Card key={item.id} className={`p-4 ${item.mastered ? "opacity-50" : ""}`}>
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    {item.mastered && <Badge className="bg-green-500">已掌握</Badge>}
                    <Badge variant="outline" className="text-xs">
                      {TYPE_LABELS[item.type] || item.type}
                    </Badge>
                  </div>
                  {item.content && <p className="font-medium">{item.content}</p>}
                  <div className="mt-2 text-sm">
                    <p className="text-red-500">你的答案：{item.userAnswer || "未作答"}</p>
                    <p className="text-green-600">正确答案：{item.answer}</p>
                    {item.explanation && (
                      <p className="text-gray-500 mt-1 text-xs">💡 {item.explanation}</p>
                    )}
                  </div>
                </div>
                {!item.mastered && (
                  <Button size="sm" variant="outline" onClick={() => markMastered(item.id)}>
                    标记掌握
                  </Button>
                )}
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
