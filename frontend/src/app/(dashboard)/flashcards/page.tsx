"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

interface Stats {
  total: number;
  due: number;
}

export default function FlashcardsPage() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    api.flashcards.list().then((d: any) => setStats(d.stats)).finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="space-y-2">{Array.from({length:3}).map((_,i)=><Skeleton key={i} className="h-20 w-full" />)}</div>;

  return (
    <div className="max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-semibold">闪卡记忆</h2>

      <div className="grid grid-cols-2 gap-4">
        <Card className="p-6 text-center">
          <p className="text-3xl font-bold text-blue-600">
            {stats?.total ?? 0}
          </p>
          <p className="text-sm text-gray-400 mt-1">总闪卡数</p>
        </Card>
        <Card className="p-6 text-center">
          <p className="text-3xl font-bold text-orange-600">
            {stats?.due ?? 0}
          </p>
          <p className="text-sm text-gray-400 mt-1">待复习</p>
        </Card>
      </div>

      <p className="text-sm text-gray-500">
        闪卡基于文档知识点自动生成，用间隔重复算法帮你高效记忆。每天的待复习闪卡数量由 SM-2
        算法根据你的掌握程度自动调整。
      </p>

      <Button
        className="w-full"
        size="lg"
        disabled={stats?.due === 0}
        onClick={() => router.push("/flashcards/review")}
      >
        {stats && stats.due > 0
          ? `开始复习（${stats.due} 张待复习）`
          : "暂无待复习闪卡"}
      </Button>

      <Button variant="outline" className="w-full" onClick={() => router.push("/documents")}>
        去文档页生成闪卡
      </Button>
    </div>
  );
}
