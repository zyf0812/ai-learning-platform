"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { ReactFlow, MiniMap, Controls, Background, type Node, type Edge } from "@xyflow/react";
import "@xyflow/react/dist/style.css";

export default function MindmapPage() {
  const params = useParams();
  const router = useRouter();
  const [nodes, setNodes] = useState<Node[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const token = localStorage.getItem("token");
    fetch(`/api/documents/${params.id}/knowledge`, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((r) => r.json())
      .then((d) => {
        const points = d.points || [];
        if (points.length === 0) { setLoading(false); return; }

        // 中心节点
        const centerX = 400, centerY = 300, radius = 250;
        const newNodes: Node[] = [];
        const newEdges: Edge[] = [];

        // 添加中心节点（文档名）
        newNodes.push({
          id: "center",
          position: { x: centerX - 60, y: centerY - 20 },
          data: { label: "📄 文档" },
          style: { background: "#3b82f6", color: "#fff", padding: "12px 20px", borderRadius: "8px", fontSize: "14px" },
        });

        // 知识点环绕
        points.forEach((p: any, i: number) => {
          const angle = (2 * Math.PI * i) / points.length - Math.PI / 2;
          const x = centerX + radius * Math.cos(angle) - 50;
          const y = centerY + radius * Math.sin(angle) - 20;
          newNodes.push({
            id: p.id,
            position: { x, y },
            data: { label: p.title },
            style: { background: "#fff", border: "2px solid #3b82f6", padding: "10px 16px", borderRadius: "6px", fontSize: "12px" },
          });
          newEdges.push({
            id: `e-${p.id}`,
            source: "center",
            target: p.id,
            style: { stroke: "#94a3b8" },
          });
        });

        setNodes(newNodes);
        setEdges(newEdges);
        setLoading(false);
      });
  }, [params.id]);

  if (loading) return <p className="text-muted-foreground">加载中...</p>;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="outline" size="sm" onClick={() => router.push(`/documents/${params.id}`)}>← 返回文档</Button>
        <h2 className="text-xl font-semibold text-foreground">思维导图</h2>
      </div>
      <p className="text-sm text-muted-foreground">先到知识点复习页提取知识点，再回来看思维导图</p>
      <div style={{ width: "100%", height: 600, border: "1px solid #e2e8f0", borderRadius: 8 }}>
        <ReactFlow nodes={nodes} edges={edges} fitView>
          <Controls />
          <MiniMap />
          <Background />
        </ReactFlow>
      </div>
    </div>
  );
}
