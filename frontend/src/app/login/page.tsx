"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth-context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ArrowRight, Brain, FileText, Layers3, ShieldCheck } from "lucide-react";
import Link from "next/link";

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "请求失败";
}

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
      localStorage.removeItem("session_expired");
      queueMicrotask(() => setExpired(true));
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await login(username, password);
      router.push("/");
    } catch (err: unknown) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="app-shell min-h-screen overflow-auto">
      <main className="relative z-10 grid min-h-screen items-center gap-8 px-6 py-10 lg:grid-cols-[1fr_430px] lg:px-14">
        <section className="hidden max-w-3xl space-y-8 lg:block">
          <div className="space-y-4">
            <div className="page-kicker">AI Learning Platform</div>
            <h1 className="text-5xl font-bold leading-tight text-foreground">
              文档、问答、出题和复习，都放进一个清爽的学习工作台。
            </h1>
            <p className="max-w-2xl text-base leading-8 text-muted-foreground">
              上传课程资料后，让 AI 帮你提取知识点、生成练习、记录错题，再用闪卡安排长期复习。
            </p>
          </div>

          <div className="grid max-w-2xl grid-cols-3 gap-3">
            {[
              { icon: FileText, title: "文档解析", desc: "PDF / Word / PPT" },
              { icon: Brain, title: "AI 问答", desc: "围绕资料追问" },
              { icon: Layers3, title: "闪卡复习", desc: "间隔重复记忆" },
            ].map((item) => {
              const Icon = item.icon;
              return (
                <div key={item.title} className="surface-panel rounded-2xl p-4">
                  <Icon className="mb-4 size-5 text-primary" />
                  <div className="font-semibold text-foreground">{item.title}</div>
                  <div className="mt-1 text-xs text-muted-foreground">{item.desc}</div>
                </div>
              );
            })}
          </div>
        </section>

        <section className="surface-panel mx-auto w-full max-w-md rounded-2xl p-6 sm:p-8">
          <div className="mb-8">
            <div className="mb-5 flex size-12 items-center justify-center rounded-2xl bg-slate-950 text-white shadow-lg shadow-slate-900/10">
              <Brain className="size-6" />
            </div>
            <h2 className="text-2xl font-bold text-foreground">登录 AI 学习平台</h2>
            <p className="mt-2 text-sm text-muted-foreground">继续你的文档学习、练习和复盘。</p>
          </div>

          {expired && (
            <div className="mb-4 flex gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              <ShieldCheck className="mt-0.5 size-4 shrink-0" />
              登录已过期，请重新登录。
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">用户名</label>
              <Input
                placeholder="输入用户名"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                className="h-11 bg-white/70"
              />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">密码</label>
              <Input
                type="password"
                placeholder="输入密码"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                className="h-11 bg-white/70"
              />
            </div>
            {error && <p className="rounded-xl bg-red-50 p-3 text-sm text-red-600">{error}</p>}
            <Button type="submit" className="h-11 w-full bg-slate-950 hover:bg-slate-800" disabled={submitting}>
              {submitting ? "登录中..." : "登录"}
              {!submitting && <ArrowRight className="size-4" />}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            还没有账号？{" "}
            <Link href="/register" className="font-semibold text-primary hover:underline">
              注册
            </Link>
          </p>
        </section>
      </main>
    </div>
  );
}
