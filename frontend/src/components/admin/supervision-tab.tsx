"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { Skeleton } from "@/components/ui/skeleton";

export default function SupervisionTab() {
  const [code, setCode] = useState("");
  const [users, setUsers] = useState<any[]>([]);
  const [msg, setMsg] = useState("");
  const [loading, setLoading] = useState(true);

  const loadData = async () => {
    setLoading(true);
    try {
      const [c, u] = await Promise.all([api.supervision.code(), api.supervision.users()]);
      if (c.code) setCode(c.code);
      setUsers(u.users || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, []);

  const approve = async (id: string) => { await api.supervision.approve(id); loadData(); };
  const reject = async (id: string) => { await api.supervision.reject(id); loadData(); };
  const remove = async (id: string) => { await api.supervision.remove(id); loadData(); };

  if (loading) return (
    <div className="space-y-3">
      <Skeleton className="h-24 w-full" />
      <Skeleton className="h-16 w-full" />
      <Skeleton className="h-16 w-full" />
    </div>
  );

  const pending = users.filter(u => u.status === "pending");
  const approved = users.filter(u => u.status === "approved");

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <p className="text-sm font-medium mb-2">你的监管码</p>
        <div className="flex items-center gap-2">
          <Input value={code} readOnly className="font-mono text-lg text-center" />
          <Button size="sm" variant="outline" onClick={() => { navigator.clipboard.writeText(code); setMsg("已复制"); }}>复制</Button>
        </div>
        <p className="text-xs text-muted-foreground mt-1">将此码发给用户，对方在"加入监管"中输入即可申请</p>
      </Card>

      {msg && <p className="text-green-600 text-sm">{msg}</p>}

      {pending.length > 0 && (
        <div>
          <p className="font-semibold mb-2">待审批 ({pending.length})</p>
          {pending.map(u => (
            <Card key={u.id} className="p-3 flex items-center justify-between mb-2">
              <span>{u.username}</span>
              <div className="flex gap-2">
                <Button size="sm" onClick={() => approve(u.id)}>批准</Button>
                <Button size="sm" variant="destructive" className="text-white" onClick={() => reject(u.id)}>拒绝</Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <div>
        <p className="font-semibold mb-2">已监管用户 ({approved.length})</p>
        {approved.map(u => (
          <Card key={u.id} className="p-3 flex items-center justify-between mb-2">
            <span>{u.username}</span>
            <Button size="sm" variant="destructive" className="text-white" onClick={() => remove(u.id)}>移除</Button>
          </Card>
        ))}
      </div>
    </div>
  );
}
