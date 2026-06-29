"use client";

import { useState } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export function JoinSupervisionDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [code, setCode] = useState("");
  const [msg, setMsg] = useState("");
  const [loading, setLoading] = useState(false);

  const join = async () => {
    if (!code.trim()) return;
    setLoading(true);
    const token = localStorage.getItem("token");
    const r = await fetch("/api/supervision/join", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ code: code.trim() }),
    });
    const d = await r.json();
    setMsg(d.message || d.error);
    setLoading(false);
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader><DialogTitle>加入管理员监管</DialogTitle></DialogHeader>
        <p className="text-sm text-muted-foreground">输入管理员提供的监管码</p>
        <Input placeholder="8位监管码" value={code} onChange={e => setCode(e.target.value)} maxLength={8} onKeyDown={e => e.key === "Enter" && join()} />
        {msg && <p className={`text-sm ${msg.includes("已") ? "text-green-600" : "text-red-500"}`}>{msg}</p>}
        <Button onClick={join} disabled={loading}>{loading ? "申请中..." : "申请加入"}</Button>
      </DialogContent>
    </Dialog>
  );
}
