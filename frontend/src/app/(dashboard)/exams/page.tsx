"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { api } from "@/lib/api";

export default function ExamsPage() {
  const [exams, setExams] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const router = useRouter();

  const load = useCallback(async () => {
    const res = await api.exams.list() as any;
    setExams(res.exams);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await api.exams.delete(deleteTarget);
    setDeleteTarget(null);
    load();
  };

  if (loading) return <div className="space-y-2">{Array.from({length:3}).map((_,i)=><Skeleton key={i} className="h-20 w-full" />)}</div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-semibold">考试列表</h2>
        <Button onClick={() => router.push("/exams/generate")}>生成考题</Button>
      </div>
      {exams.length === 0 ? (
        <Card className="p-12 text-center text-muted-foreground">
          <p className="text-lg mb-2">还没有考试</p>
          <p>上传文档后生成考题，然后来这里考试</p>
        </Card>
      ) : (
        <div className="grid gap-3">
          {exams.map((exam: any) => (
            <Card key={exam.id} className="p-4 flex items-center justify-between hover:shadow-md cursor-pointer"
              onClick={() => router.push(`/exams/${exam.id}`)}>
              <div>
                <h3 className="font-semibold">{exam.title}</h3>
                <p className="text-xs text-muted-foreground">
                  {exam.documents?.map((d: any) => d.document?.title || d.title).filter(Boolean).join(", ") || exam.title}
                </p>
              </div>
              <div className="flex gap-2" onClick={e => e.stopPropagation()}>
                <Button size="sm" onClick={() => router.push(`/exams/${exam.id}`)}>查看</Button>
                <Button variant="destructive" size="sm" className="text-white" onClick={() => setDeleteTarget(exam.id)}>删除</Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={!!deleteTarget}
        title="删除考试"
        message="确认删除此考试？所有题目将被永久删除。"
        confirmText="删除"
        danger
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
