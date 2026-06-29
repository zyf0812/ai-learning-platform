"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

const QUESTION_TYPES = [
  { key: "choice", label: "选择题" },
  { key: "fill", label: "填空题" },
  { key: "truefalse", label: "判断题" },
  { key: "shortanswer", label: "简答题" },
];

export default function GenerateExamPage() {
  const [title, setTitle] = useState("");
  const [typeCounts, setTypeCounts] = useState<Record<string, number>>({ choice: 3, fill: 2, truefalse: 2 });
  const [docs, setDocs] = useState<{ id: string; title: string; fileType: string }[]>([]);
  const [selectedDocs, setSelectedDocs] = useState<string[]>([]);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState("");
  const router = useRouter();

  useEffect(() => {
    const token = localStorage.getItem("token");
    fetch("/api/documents", {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((r) => r.json())
      .then((d) => setDocs(d.documents));
  }, []);

  const toggleDoc = (id: string) => {
    setSelectedDocs((prev) =>
      prev.includes(id) ? prev.filter((d) => d !== id) : [...prev, id]
    );
  };

  const setTypeCount = (key: string, val: number) => {
    const updated = { ...typeCounts };
    if (val <= 0) {
      delete updated[key];
    } else {
      updated[key] = Math.min(val, 30);
    }
    setTypeCounts(updated);
  };

  const handleGenerate = async () => {
    if (!title.trim()) { setError("请输入考试名称"); return; }
    if (Object.keys(typeCounts).length === 0) { setError("请至少设置一种题型"); return; }
    if (selectedDocs.length === 0) { setError("请至少选择一个文档"); return; }
    setGenerating(true);
    setError("");
    try {
      const token = localStorage.getItem("token");
      const types = Object.keys(typeCounts);
      const count = Object.values(typeCounts).reduce((a, b) => a + b, 0);
      const res = await fetch("/api/exams/generate", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ title: title.trim(), documentIds: selectedDocs, types, count, typeCounts }),
      });
      const data = await res.json();
      if (data.error) throw new Error(data.error);

      const examId = data.exam.id;
      const poll = setInterval(async () => {
        const sr = await fetch(`/api/exams/${examId}/status`, { headers: { Authorization: `Bearer ${token}` } });
        const sd = await sr.json();
        if (!sd.status) return;
        if (sd.status.startsWith("DONE:")) { clearInterval(poll); router.push(`/exams/${examId}`); }
        else if (sd.status.startsWith("FAILED:") || sd.status.startsWith("ERROR:")) { clearInterval(poll); setError(sd.status.split(":")[1] || "出题失败"); setGenerating(false); }
      }, 2000);
    } catch (err: any) {
      setError(err.message); setGenerating(false);
    }
  };

  const total = Object.values(typeCounts).reduce((a, b) => a + b, 0);

  return (
    <div className="max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-semibold text-foreground">生成考题</h2>
      <Card className="p-6 space-y-4">
        <div>
          <label className="text-sm font-medium mb-1 block text-foreground">考试名称</label>
          <Input placeholder="例如：综合测试" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>

        <div>
          <label className="text-sm font-medium mb-2 block text-foreground">选择文档（多选）</label>
          <div className="max-h-40 overflow-y-auto space-y-1 border rounded p-2">
            {docs.length === 0 ? (
              <p className="text-sm text-muted-foreground">暂无文档，请先上传</p>
            ) : (
              docs.map((doc) => (
                <label key={doc.id} className="flex items-center gap-2 cursor-pointer text-sm p-1 hover:bg-muted rounded">
                  <input type="checkbox" checked={selectedDocs.includes(doc.id)} onChange={() => toggleDoc(doc.id)} />
                  <span className="text-foreground">{doc.title}</span>
                </label>
              ))
            )}
          </div>
        </div>

        <div>
          <label className="text-sm font-medium mb-2 block text-foreground">题目类型与数量</label>
          <div className="space-y-2">
            {QUESTION_TYPES.map((t) => (
              <div key={t.key} className="flex items-center gap-3">
                <Badge
                  variant={typeCounts[t.key] ? "default" : "outline"}
                  className="cursor-pointer w-20 justify-center"
                  onClick={() => typeCounts[t.key] ? setTypeCount(t.key, 0) : setTypeCount(t.key, 3)}
                >
                  {t.label}
                </Badge>
                {typeCounts[t.key] ? (
                  <div className="flex items-center gap-1">
                    <Button variant="outline" size="sm" className="h-7 w-7 p-0" onClick={() => setTypeCount(t.key, (typeCounts[t.key] || 3) - 1)}>-</Button>
                    <Input type="number" className="w-16 h-7 text-center text-sm" value={typeCounts[t.key]} min={1} max={30}
                      onChange={e => setTypeCount(t.key, parseInt(e.target.value) || 0)} />
                    <Button variant="outline" size="sm" className="h-7 w-7 p-0" onClick={() => setTypeCount(t.key, (typeCounts[t.key] || 0) + 1)}>+</Button>
                    <span className="text-xs text-muted-foreground">题</span>
                  </div>
                ) : (
                  <span className="text-xs text-muted-foreground">点击添加此题型</span>
                )}
              </div>
            ))}
          </div>
          {total > 0 && <p className="text-xs text-muted-foreground mt-1">共 {total} 题</p>}
        </div>

        {error && <p className="text-red-500 text-sm bg-red-50 dark:bg-red-950 p-2 rounded">{error}</p>}

        <Button className="w-full" onClick={handleGenerate} disabled={generating || total === 0}>
          {generating ? "AI 正在生成考题..." : `生成考题（${total}题）`}
        </Button>
      </Card>
    </div>
  );
}
