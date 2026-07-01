"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import Link from "next/link";

export default function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const { login } = useAuth();
  const router = useRouter();
  const [expired, setExpired] = useState(false);

  useEffect(() => {
    if (typeof window !== "undefined" && localStorage.getItem("session_expired") === "1") {
      setExpired(true);
      localStorage.removeItem("session_expired");
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await login(username, password);
      router.push("/");
    } catch (err: any) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen flex bg-gradient-to-br from-background via-background to-muted/30">
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="w-full max-w-sm">
          <div className="text-center mb-8">
            <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center text-white text-xl font-bold mx-auto mb-4 shadow-lg shadow-blue-500/20">A</div>
            <h1 className="text-xl font-bold text-foreground">登录 AI 学习平台</h1>
            <p className="text-sm text-muted-foreground mt-1.5">上传文档 · AI 出题 · 在线考试</p>
          </div>

          {expired && (
            <div className="bg-amber-50/80 dark:bg-amber-950/50 border border-amber-200 dark:border-amber-800 text-amber-700 dark:text-amber-300 p-3 rounded-xl text-sm mb-4 slide-up">
              ⏰ 登录已过期。为保护你的账号安全，连续 2 小时无操作后需要重新登录。
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4 bg-card border rounded-2xl shadow-sm p-6">
            <div>
              <label className="text-sm font-medium text-foreground/80 mb-1.5 block">用户名</label>
              <Input placeholder="输入用户名" value={username} onChange={(e) => setUsername(e.target.value)} required className="h-10" />
            </div>
            <div>
              <label className="text-sm font-medium text-foreground/80 mb-1.5 block">密码</label>
              <Input type="password" placeholder="输入密码" value={password} onChange={(e) => setPassword(e.target.value)} required className="h-10" />
            </div>
            {error && <p className="text-destructive text-sm bg-destructive/10 p-2.5 rounded-lg">{error}</p>}
            <Button type="submit" className="w-full h-10" disabled={submitting}>
              {submitting ? "登录中..." : "登录"}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            还没有账号？{" "}
            <Link href="/register" className="text-primary font-medium hover:underline">注册</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
