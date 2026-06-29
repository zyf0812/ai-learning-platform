"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";

interface GroupNotifyProps {
  groupId: string;
  members: any[];
  onSent: () => void;
}

export default function GroupNotify({ groupId, members, onSent }: GroupNotifyProps) {
  const approved = members.filter((m: any) => m.status === "approved");
  const [notifyTitle, setNotifyTitle] = useState("");
  const [notifyContent, setNotifyContent] = useState("");
  const [checkedUsers, setCheckedUsers] = useState<string[]>([]);
  const [msg, setMsg] = useState("");

  const send = async () => {
    if (!notifyTitle.trim() || checkedUsers.length === 0) return;
    await api.groups.chat.notify(groupId, { title: notifyTitle, content: notifyContent, userIds: checkedUsers });
    setNotifyTitle("");
    setNotifyContent("");
    setCheckedUsers([]);
    setMsg("通知已发送");
    onSent();
  };

  return (
    <Card className="p-4 space-y-3">
      <h3 className="font-semibold">发送群通知</h3>
      {msg && <p className="text-green-600 text-sm">{msg}</p>}
      <div className="max-h-40 overflow-y-auto border rounded p-2 space-y-1">
        {approved.map((m: any) => (
          <label key={m.userId} className="flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={checkedUsers.includes(m.userId)}
              onChange={() => setCheckedUsers(p => p.includes(m.userId) ? p.filter(x => x !== m.userId) : [...p, m.userId])}
            />
            {m.username}
          </label>
        ))}
      </div>
      <Input placeholder="标题" value={notifyTitle} onChange={e => setNotifyTitle(e.target.value)} />
      <Input placeholder="内容" value={notifyContent} onChange={e => setNotifyContent(e.target.value)} />
      <Button onClick={send} disabled={checkedUsers.length === 0}>发送通知 ({checkedUsers.length}人)</Button>
    </Card>
  );
}
