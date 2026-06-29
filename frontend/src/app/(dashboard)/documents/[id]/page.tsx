"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import ReactMarkdown from "react-markdown";
import { Skeleton } from "@/components/ui/skeleton";

interface Doc {
  id: string;
  title: string;
  originalFilename: string;
  fileType: string;
  content: string;
  createdAt: string;
}

export default function DocumentDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [doc, setDoc] = useState<Doc | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.documents
      .get(params.id as string)
      .then((d) => setDoc(d.document))
      .catch(() => router.push("/documents"))
      .finally(() => setLoading(false));
  }, [params.id, router]);

  if (loading) return <Skeleton className="h-4 w-48" />;
  if (!doc) return null;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div>
          <h2 className="text-xl font-semibold">{doc.title}</h2>
          <p className="text-sm text-muted-foreground">
            {doc.originalFilename} · {doc.fileType.toUpperCase()} · 上传于{" "}
            {new Date(doc.createdAt).toLocaleString("zh-CN")}
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            onClick={() => router.push(`/documents/${doc.id}/knowledge`)}
          >
            知识点复习
          </Button>
          <Button
            onClick={() => router.push(`/documents/${doc.id}/mindmap`)}
            variant="outline"
          >
            思维导图
          </Button>
          <Button
            onClick={() => router.push("/documents")}
            variant="outline"
          >
            返回
          </Button>
        </div>
      </div>

      <Card className="p-6 prose prose-sm max-w-none">
        <ReactMarkdown>
          {doc.content.slice(0, 5000)}
        </ReactMarkdown>
        {doc.content.length > 5000 && (
          <p className="text-muted-foreground mt-4">
            （内容过长，仅显示前 5000 字。完整内容已参与 RAG 索引）
          </p>
        )}
      </Card>
    </div>
  );
}
