"use client";

import { useState, useEffect, Suspense, lazy } from "react";

const ReactECharts = lazy(() => import("echarts-for-react"));

function LoadingSkeleton() {
  return (
    <div className="space-y-6 animate-pulse">
      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        {[1,2,3,4,5].map(i => <div key={i} className="h-20 bg-gray-100 dark:bg-gray-800 rounded-xl" />)}
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {[1,2,3].map(i => <div key={i} className="h-64 bg-gray-100 dark:bg-gray-800 rounded-xl" />)}
      </div>
    </div>
  );
}

export default function StatsPage() {
  const [loading, setLoading] = useState(true);
  const [chartData, setChartData] = useState<any>(null);

  useEffect(() => {
    const token = localStorage.getItem("token");
    Promise.all([
      fetch("/api/stats", { headers: { Authorization: `Bearer ${token}` } }).then(r => r.json()),
      fetch("/api/exams", { headers: { Authorization: `Bearer ${token}` } }).then(r => r.json()),
      fetch("/api/wrong-questions", { headers: { Authorization: `Bearer ${token}` } }).then(r => r.json()),
    ]).then(([statsRes, examsRes, wqRes]) => {
      const stats = statsRes.stats || {};
      const wqs = wqRes.wrongQuestions || [];
      const typeCount: Record<string, number> = {};
      wqs.forEach((w: any) => { const t = w.type || "unknown"; typeCount[t] = (typeCount[t] || 0) + 1; });
      setChartData({ stats, typeCount });
      setLoading(false);
    });
  }, []);

  if (loading) return <LoadingSkeleton />;
  if (!chartData) return null;

  const { stats, typeCount } = chartData;
  const s = stats || {};
  const scoreBar = Math.min(Math.round(s.avgScore || 0), 100);

  const typeLabels: Record<string, string> = { choice: "选择题", fill: "填空题", truefalse: "判断题", shortanswer: "简答题" };

  const barOption = {
    tooltip: { trigger: "axis" as const },
    grid: { left: 40, right: 20, top: 10, bottom: 20 },
    xAxis: { type: "category" as const, data: ["文档", "考试", "答题", "闪卡", "错题"], axisLabel: { fontSize: 11 } },
    yAxis: { type: "value" as const, axisLabel: { fontSize: 10 } },
    series: [{
      type: "bar" as const, barWidth: 24, animationDuration: 500,
      itemStyle: { borderRadius: [6, 6, 0, 0] },
      data: [
        { value: s.documentCount || 0, itemStyle: { color: "#3b82f6" } },
        { value: s.examCount || 0, itemStyle: { color: "#8b5cf6" } },
        { value: s.attemptCount || 0, itemStyle: { color: "#10b981" } },
        { value: s.flashcardCount || 0, itemStyle: { color: "#f59e0b" } },
        { value: s.wrongCount || 0, itemStyle: { color: "#ef4444" } },
      ],
    }],
  };

  const pieOption = {
    tooltip: { trigger: "item" as const },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: "pie" as const, radius: ["55%", "75%"], center: ["50%", "45%"], animationDuration: 500,
      itemStyle: { borderRadius: 6, borderColor: "#fff", borderWidth: 2 },
      label: { show: false },
      data: [
        { value: scoreBar, name: "正确率", itemStyle: { color: "#10b981" } },
        { value: 100 - scoreBar, name: "提升空间", itemStyle: { color: "#e5e7eb" } },
      ],
    }],
  };

  const typePieOption = {
    tooltip: { trigger: "item" as const },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: "pie" as const, radius: "70%", center: ["50%", "45%"], animationDuration: 500,
      itemStyle: { borderRadius: 4, borderColor: "#fff", borderWidth: 2 },
      label: { formatter: "{b}\n{d}%", fontSize: 11 },
      data: Object.entries(typeCount).map(([k, v]) => ({ value: v, name: typeLabels[k] || k })),
    }],
  };

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold text-foreground">学习统计</h2>
        <p className="text-sm text-muted-foreground mt-1">全面了解你的学习数据</p>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
        {[
          { v: s.documentCount || 0, l: "文档", c: "blue" },
          { v: s.attemptCount || 0, l: "答题次数", c: "emerald" },
          { v: `${scoreBar}%`, l: "正确率", c: "violet" },
          { v: s.flashcardCount || 0, l: "闪卡", c: "amber" },
          { v: s.wrongCount || 0, l: "待巩固", c: "rose" },
        ].map(({ v, l, c }) => {
          const colors: Record<string, string> = {
            blue: "bg-blue-50 dark:bg-blue-950 text-blue-700 dark:text-blue-300",
            emerald: "bg-emerald-50 dark:bg-emerald-950 text-emerald-700 dark:text-emerald-300",
            violet: "bg-violet-50 dark:bg-violet-950 text-violet-700 dark:text-violet-300",
            amber: "bg-amber-50 dark:bg-amber-950 text-amber-700 dark:text-amber-300",
            rose: "bg-rose-50 dark:bg-rose-950 text-rose-700 dark:text-rose-300",
          };
          return (
            <div key={l} className={`${colors[c]} rounded-xl p-4 text-center`}>
              <p className="text-2xl font-bold">{v}</p>
              <p className="text-xs mt-1 opacity-70">{l}</p>
            </div>
          );
        })}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="bg-card border rounded-xl p-5">
          <h3 className="font-semibold text-foreground mb-2">数据概览</h3>
          <Suspense fallback={<div className="h-[220px] bg-muted rounded animate-pulse" />}>
            <ReactECharts option={barOption} style={{ height: 220 }} notMerge lazyUpdate />
          </Suspense>
        </div>
        <div className="bg-card border rounded-xl p-5">
          <h3 className="font-semibold text-foreground mb-2">正确率</h3>
          <Suspense fallback={<div className="h-[220px] bg-muted rounded animate-pulse" />}>
            <ReactECharts option={pieOption} style={{ height: 220 }} notMerge lazyUpdate />
          </Suspense>
        </div>
        <div className="bg-card border rounded-xl p-5">
          <h3 className="font-semibold text-foreground mb-2">错题题型分布</h3>
          <Suspense fallback={<div className="h-[220px] bg-muted rounded animate-pulse" />}>
            {Object.keys(typeCount).length > 0 ? (
              <ReactECharts option={typePieOption} style={{ height: 220 }} notMerge lazyUpdate />
            ) : (
              <div className="h-[220px] flex items-center justify-center text-muted-foreground text-sm">暂无错题数据</div>
            )}
          </Suspense>
        </div>
      </div>

      <div className="bg-card border rounded-xl p-5">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-semibold text-foreground">总体正确率</h3>
          <span className={`text-sm font-bold ${scoreBar >= 80 ? "text-emerald-600" : scoreBar >= 60 ? "text-amber-600" : "text-rose-600"}`}>
            {scoreBar}%
          </span>
        </div>
        <div className="w-full h-3 bg-muted rounded-full overflow-hidden">
          <div className={`h-full rounded-full transition-all duration-700 ${
            scoreBar >= 80 ? "bg-gradient-to-r from-emerald-400 to-emerald-500" :
            scoreBar >= 60 ? "bg-gradient-to-r from-amber-400 to-orange-500" :
            "bg-gradient-to-r from-rose-400 to-red-500"}`}
            style={{ width: `${scoreBar}%` }} />
        </div>
      </div>
    </div>
  );
}
