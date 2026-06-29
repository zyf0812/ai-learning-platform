"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import ReactMarkdown from "react-markdown";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

interface KnowledgePoint {
  id: string;
  title: string;
  content: string;
  order: number;
}

export default function KnowledgePage() {
  const params = useParams();
  const router = useRouter();
  const [points, setPoints] = useState<KnowledgePoint[]>([]);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const data = await api.documents.knowledge.list(params.id as string);
      if (data.points) setPoints(data.points);
    } catch {}
    setLoading(false);
  }, [params.id]);

  useEffect(() => {
    load();
  }, [load]);

  const handleGenerate = async () => {
    setGenerating(true);
    setError("");
    try {
      const data = await api.documents.knowledge.generate(params.id as string);
      if (data.error) throw new Error(data.error);
      setPoints(data.points);
    } catch (err: any) {
      setError(err.message);
    } finally {
      setGenerating(false);
    }
  };

  if (loading) return <Skeleton className="h-4 w-48" />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold">知识点复习</h2>
          <p className="text-sm text-muted-foreground">
            {points.length > 0
              ? `共 ${points.length} 个知识点`
              : "尚未提取知识点"}
          </p>
        </div>
        <div className="flex gap-2">
          {points.length === 0 && (
            <Button onClick={handleGenerate} disabled={generating}>
              {generating ? "AI 提取中..." : "AI 提取知识点"}
            </Button>
          )}
          <Button variant="outline" onClick={() => router.back()}>
            返回
          </Button>
        </div>
      </div>

      {error && (
        <Card className="p-4 text-red-500 text-sm">{error}</Card>
      )}

      {generating && (
        <Card className="p-12 text-center text-muted-foreground">
          <p className="text-lg mb-2">🤖 AI 正在分析文档...</p>
          <p>正在提取关键知识点，请稍候</p>
        </Card>
      )}

      <div className="grid gap-3">
        {points.map((point) => (
          <Card
            key={point.id}
            className={`p-4 cursor-pointer transition hover:shadow-md ${
              expandedId === point.id ? "ring-2 ring-blue-300" : ""
            }`}
            onClick={() =>
              setExpandedId(expandedId === point.id ? null : point.id)
            }
          >
            <div className="flex items-center gap-2">
              <Badge variant="outline">{point.order + 1}</Badge>
              <h3 className="font-semibold">{point.title}</h3>
              <span className="text-xs text-muted-foreground ml-auto">
                {expandedId === point.id ? "收起 ▲" : "展开 ▼"}
              </span>
            </div>
            {expandedId === point.id && (
              <div className="mt-3 prose prose-sm max-w-none pt-3 border-t">
                <ReactMarkdown>{point.content}</ReactMarkdown>
              </div>
            )}
          </Card>
        ))}
      </div>

      {points.length === 0 && !generating && (
        <Card className="p-12 text-center text-muted-foreground">
          <p className="text-lg mb-2">🧠 暂无知识点</p>
          <p>点击"AI 提取知识点"让 DeepSeek 分析文档内容</p>
        </Card>
      )}
    </div>
  );
}
