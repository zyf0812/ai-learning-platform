"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth-context";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import Link from "next/link";

export default function RegisterPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [role, setRole] = useState("user");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const { register } = useAuth();
  const router = useRouter();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(""); setSuccess("");
    if (password !== confirm) { setError("两次密码不一致"); return; }
    if (password.length < 6) { setError("密码至少6位"); return; }
    setSubmitting(true);
    try {
      const result = await api.register({ username, password, role });
      if (role === "admin" || result.message) {
        setSuccess(result.message || "管理员申请已提交，请等待 root 审批");
        return;
      }
      router.push("/");
    } catch (err: any) {
      setError(err.message);
    } finally { setSubmitting(false); }
  };

  return (
    <div className="min-h-screen flex bg-gradient-to-br from-background via-background to-muted/30">
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="w-full max-w-sm">
          <div className="text-center mb-8">
            <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center text-white text-xl font-bold mx-auto mb-4 shadow-lg shadow-blue-500/20">A</div>
            <h1 className="text-xl font-bold text-foreground">注册 AI 学习平台</h1>
            <p className="text-sm text-muted-foreground mt-1.5">创建账号开始学习</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4 bg-card border rounded-2xl shadow-sm p-6">
            <div>
              <label className="text-sm font-medium text-foreground/80 mb-1.5 block">用户名</label>
              <Input placeholder="至少3位" value={username} onChange={(e) => setUsername(e.target.value)} required minLength={3} className="h-10" />
            </div>
            <div>
              <label className="text-sm font-medium text-foreground/80 mb-1.5 block">密码</label>
              <Input type="password" placeholder="至少6位" value={password} onChange={(e) => setPassword(e.target.value)} required className="h-10" />
            </div>
            <div>
              <label className="text-sm font-medium text-foreground/80 mb-1.5 block">确认密码</label>
              <Input type="password" placeholder="再输一次" value={confirm} onChange={(e) => setConfirm(e.target.value)} required className="h-10" />
            </div>
            {error && <p className="text-destructive text-sm bg-destructive/10 p-2.5 rounded-lg">{error}</p>}
            {success && <div className="bg-emerald-50/80 dark:bg-emerald-950/50 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-300 p-3 rounded-xl text-sm slide-up">✅ {success}</div>}
            <div>
              <label className="text-sm font-medium text-foreground/80 mb-1.5 block">账号类型</label>
              <div className="flex gap-2">
                <label className={`flex-1 text-center text-sm py-2 rounded-xl border cursor-pointer transition-all duration-150 ${role === "user" ? "bg-primary/10 border-primary/30 text-primary font-medium" : "border-border text-muted-foreground hover:bg-muted/60"}`}>
                  <input type="radio" name="role" value="user" className="hidden" checked={role === "user"} onChange={() => setRole("user")} />
                  普通用户
                </label>
                <label className={`flex-1 text-center text-sm py-2 rounded-xl border cursor-pointer transition-all duration-150 ${role === "admin" ? "bg-violet-100 dark:bg-violet-900/30 border-violet-300 dark:border-violet-700 text-violet-700 dark:text-violet-300 font-medium" : "border-border text-muted-foreground hover:bg-muted/60"}`}>
                  <input type="radio" name="role" value="admin" className="hidden" checked={role === "admin"} onChange={() => setRole("admin")} />
                  管理员
                </label>
              </div>
              {role === "admin" && <p className="text-xs text-amber-500 mt-1.5">管理员需要 root 审批后才能激活</p>}
            </div>
            <Button type="submit" className="w-full h-10" disabled={submitting}>
              {submitting ? "注册中..." : "注册"}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            已有账号？{" "}
            <Link href="/login" className="text-primary font-medium hover:underline">登录</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
