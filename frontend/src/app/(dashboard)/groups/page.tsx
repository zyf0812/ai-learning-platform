"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { api } from "@/lib/api";

export default function GroupsPage() {
  const [groups, setGroups] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [joinOpen, setJoinOpen] = useState(false);
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState("");
  const router = useRouter();

  const load = async () => {
    const r = await api.groups.list() as any;
    setGroups(r.groups || []);
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const create = async () => {
    if (!name.trim()) return;
    const d = await api.groups.create(name) as any;
    if (d.error) { setMsg(d.error); return; }
    setCreateOpen(false); setName(""); setMsg("");
    load();
  };

  const join = async () => {
    if (!code.trim()) return;
    const d = await api.groups.join(code) as any;
    if (d.error) { setMsg(d.error); return; }
    setJoinOpen(false); setCode(""); setMsg(d.message || "已申请");
    load();
  };

  if (loading) return <div className="space-y-2">{Array.from({length:3}).map((_,i)=><Skeleton key={i} className="h-20 w-full" />)}</div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-semibold text-foreground">学习群组</h2>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => setJoinOpen(true)}>加入群组</Button>
          <Button size="sm" onClick={() => setCreateOpen(true)}>创建群组</Button>
        </div>
      </div>

      {msg && <div className="bg-blue-50 dark:bg-blue-950 text-blue-700 dark:text-blue-300 p-2 rounded text-sm">{msg}</div>}

      {groups.length === 0 ? (
        <Card className="p-12 text-center text-muted-foreground">
          <p className="text-lg mb-2">👥 还没有群组</p>
          <p className="text-sm">创建或加入一个学习群组</p>
        </Card>
      ) : (
        <div className="grid gap-3">
          {groups.map(g => (
            <Card key={g.id} className="p-4 cursor-pointer hover:shadow-md" onClick={() => router.push(`/groups/${g.id}`)}>
              <div className="flex items-center justify-between">
                <div>
                  <p className="font-semibold text-foreground">{g.name}</p>
                  <p className="text-xs text-muted-foreground">群主: {g.adminName} · 加入码: {g.code}</p>
                </div>
                <span className="text-muted-foreground text-sm">→</span>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>创建群组</DialogTitle></DialogHeader>
          <Input placeholder="群名称" value={name} onChange={e => setName(e.target.value)} onKeyDown={e => e.key === "Enter" && create()} />
          <Button onClick={create}>创建</Button>
        </DialogContent>
      </Dialog>

      <Dialog open={joinOpen} onOpenChange={setJoinOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>加入群组</DialogTitle></DialogHeader>
          <Input placeholder="输入8位加入码" value={code} onChange={e => setCode(e.target.value)} maxLength={8} onKeyDown={e => e.key === "Enter" && join()} />
          <Button onClick={join}>申请加入</Button>
        </DialogContent>
      </Dialog>
    </div>
  );
}
