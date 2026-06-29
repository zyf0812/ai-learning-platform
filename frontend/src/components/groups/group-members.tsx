"use client";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

interface GroupMembersProps {
  members: any[];
  adminUserId: string;
  onApprove: (memberId: string) => void;
  onReject: (memberId: string) => void;
  onRemove: (memberId: string) => void;
}

export default function GroupMembers({ members, adminUserId, onApprove, onReject, onRemove }: GroupMembersProps) {
  const currentUserId = typeof window !== "undefined"
    ? JSON.parse(localStorage.getItem("user") || "{}").id
    : null;
  const isAdmin = currentUserId === adminUserId;
  const pending = members.filter((m: any) => m.status === "pending");
  const approved = members.filter((m: any) => m.status === "approved");

  return (
    <div className="space-y-4">
      {pending.length > 0 && (
        <div>
          <p className="font-semibold mb-2">待审批 ({pending.length})</p>
          <div className="grid gap-2">
            {pending.map((m: any) => (
              <Card key={m.id} className="p-3 flex items-center justify-between">
                <span className="text-sm">{m.username}</span>
                {isAdmin && (
                  <div className="flex gap-2">
                    <Button size="sm" onClick={() => onApprove(m.id)}>批准</Button>
                    <Button size="sm" variant="destructive" className="text-white" onClick={() => onReject(m.id)}>拒绝</Button>
                  </div>
                )}
              </Card>
            ))}
          </div>
        </div>
      )}
      <div>
        <p className="font-semibold mb-2">成员 ({approved.length})</p>
        {approved.map((m: any) => (
          <Card key={m.id} className="p-3 flex items-center gap-3">
            <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-sm font-bold text-primary">
              {m.username?.charAt(0).toUpperCase()}
            </div>
            <span>{m.username}</span>
            <Badge variant="outline" className="ml-auto mr-2">已加入</Badge>
            {isAdmin && (
              <Button size="sm" variant="destructive" className="text-white" onClick={() => onRemove(m.id)}>移除</Button>
            )}
          </Card>
        ))}
      </div>
    </div>
  );
}
