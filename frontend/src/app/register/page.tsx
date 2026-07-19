"use client";

import { useState, useEffect, useMemo } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth-context";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ArrowRight, Brain, CheckCircle2, RefreshCw, ShieldCheck, UserRound, XCircle } from "lucide-react";
import Link from "next/link";
import Image from "next/image";

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "请求失败";
}

/** 客户端密码强度评估 */
function getPasswordStrength(password: string): { level: number; label: string; color: string } {
  let score = 0;
  if (password.length >= 8) score++;
  if (password.length >= 12) score++;
  if (/[a-z]/.test(password)) score++;
  if (/[A-Z]/.test(password)) score++;
  if (/[0-9]/.test(password)) score++;
  if (/[^a-zA-Z0-9]/.test(password)) score++;
  if (score <= 2) return { level: 0, label: "弱", color: "bg-red-500" };
  if (score <= 4) return { level: 1, label: "中", color: "bg-amber-500" };
  return { level: 2, label: "强", color: "bg-emerald-500" };
}

/** 用户名校验 */
function getUsernameError(name: string): string | null {
  if (!name) return null;
  if (name.length < 3) return "用户名至少 3 位";
  if (name.length > 20) return "用户名不超过 20 位";
  if (/[<>'"%;()&+\\|]/.test(name)) return "用户名包含非法字符";
  // SQL 注入关键词
  if (/select|insert|update|delete|drop|truncate|union|exec|--/i.test(name)) {
    return "用户名包含不允许的内容";
  }
  return null;
}

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

  // 验证码
  const [captchaToken, setCaptchaToken] = useState("");
  const [captchaImage, setCaptchaImage] = useState("");
  const [captchaCode, setCaptchaCode] = useState("");

  const fetchCaptcha = async () => {
    try {
      const data = await api.captcha();
      setCaptchaToken(data.token);
      setCaptchaImage(data.image);
    } catch {
      // 静默失败
    }
  };

  useEffect(() => {
    fetchCaptcha();
  }, []);

  // 实时校验
  const usernameError = useMemo(() => getUsernameError(username), [username]);
  const pwStrength = useMemo(() => getPasswordStrength(password), [password]);
  const pwMatchError = confirm && password !== confirm ? "两次密码不一致" : null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError("");
    setSuccess("");
    if (usernameError) { setError(usernameError); return; }
    if (password.length < 8) { setError("密码至少 8 位"); return; }
    if (pwStrength.level === 0) { setError("密码过于简单，请包含字母和数字"); return; }
    if (pwMatchError) { setError(pwMatchError); return; }
    if (!captchaCode) { setError("请输入验证码"); return; }
    setSubmitting(true);
    try {
      if (role === "admin") {
        const result = await api.register({ username, password, role, captchaToken, captchaCode });
        setSuccess(result.message || "管理员申请已提交，请等待 root 审批");
        return;
      }
      await register(username, password, role, captchaToken, captchaCode);
      router.push("/");
    } catch (err: unknown) {
      setError(getErrorMessage(err));
      fetchCaptcha(); // 刷新验证码
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="app-shell min-h-screen overflow-auto">
      <main className="relative z-10 grid min-h-screen items-center gap-8 px-6 py-10 lg:grid-cols-[1fr_430px] lg:px-14">
        <section className="hidden max-w-3xl space-y-8 lg:block">
          <div className="space-y-4">
            <div className="page-kicker">Start Learning</div>
            <h1 className="text-5xl font-bold leading-tight text-foreground">
              创建账号后，就能把你的资料变成一套可复习的知识系统。
            </h1>
            <p className="max-w-2xl text-base leading-8 text-muted-foreground">
              普通用户可以上传资料、生成练习和复习错题；管理员账号会进入审批流程。
            </p>
          </div>

          <div className="surface-panel max-w-xl rounded-2xl p-5">
            <div className="mb-4 flex items-center gap-3">
              <div className="flex size-10 items-center justify-center rounded-xl bg-emerald-600 text-white">
                <CheckCircle2 className="size-5" />
              </div>
              <div>
                <div className="font-semibold text-foreground">注册后可立即开始</div>
                <div className="text-sm text-muted-foreground">上传文档、AI 问答、生成考题、复习闪卡</div>
              </div>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-muted">
              <div className="h-full w-3/4 rounded-full bg-emerald-500" />
            </div>
          </div>
        </section>

        <section className="surface-panel mx-auto w-full max-w-md rounded-2xl p-6 sm:p-8">
          <div className="mb-8">
            <div className="mb-5 flex size-12 items-center justify-center rounded-2xl bg-slate-950 text-white shadow-lg shadow-slate-900/10">
              <Brain className="size-6" />
            </div>
            <h2 className="text-2xl font-bold text-foreground">注册 AI 学习平台</h2>
            <p className="mt-2 text-sm text-muted-foreground">创建账号，开始整理自己的学习资料。</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">用户名</label>
              <Input
                placeholder="至少 3 位，仅限字母、数字、中文和下划线"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                minLength={3}
                maxLength={20}
                className="h-11 bg-white/70"
              />
              {username && usernameError && (
                <p className="mt-1 flex items-center gap-1 text-xs text-red-500">
                  <XCircle className="size-3" /> {usernameError}
                </p>
              )}
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">密码</label>
              <Input
                type="password"
                placeholder="至少 8 位，需包含字母和数字"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                className="h-11 bg-white/70"
              />
              {password && (
                <div className="mt-1.5 space-y-1">
                  <div className="flex h-1.5 gap-1">
                    {[0, 1, 2].map((i) => (
                      <div key={i} className={`h-full flex-1 rounded-full transition-colors ${
                        i <= pwStrength.level ? pwStrength.color : "bg-gray-200"
                      }`} />
                    ))}
                  </div>
                  <p className={`text-xs ${pwStrength.level === 0 ? "text-red-500" : pwStrength.level === 1 ? "text-amber-600" : "text-emerald-600"}`}>
                    密码强度：{pwStrength.label}
                    {pwStrength.level === 0 && "（建议包含大小写字母、数字和特殊字符）"}
                  </p>
                </div>
              )}
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">确认密码</label>
              <Input
                type="password"
                placeholder="再输入一次"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                required
                className="h-11 bg-white/70"
              />
              {pwMatchError && (
                <p className="mt-1 flex items-center gap-1 text-xs text-red-500">
                  <XCircle className="size-3" /> {pwMatchError}
                </p>
              )}
            </div>

            <div>
              <label className="mb-2 block text-sm font-medium text-foreground">账号类型</label>
              <div className="grid grid-cols-2 gap-2">
                <label className={`flex cursor-pointer items-center justify-center gap-2 rounded-xl border px-3 py-3 text-sm transition ${
                  role === "user" ? "border-slate-950 bg-slate-950 text-white" : "border-border bg-white/60 text-muted-foreground hover:bg-white"
                }`}>
                  <input type="radio" name="role" value="user" className="hidden" checked={role === "user"} onChange={() => setRole("user")} />
                  <UserRound className="size-4" />
                  普通用户
                </label>
                <label className={`flex cursor-pointer items-center justify-center gap-2 rounded-xl border px-3 py-3 text-sm transition ${
                  role === "admin" ? "border-emerald-700 bg-emerald-700 text-white" : "border-border bg-white/60 text-muted-foreground hover:bg-white"
                }`}>
                  <input type="radio" name="role" value="admin" className="hidden" checked={role === "admin"} onChange={() => setRole("admin")} />
                  <ShieldCheck className="size-4" />
                  管理员
                </label>
              </div>
              {role === "admin" && <p className="mt-2 text-xs text-amber-700">管理员需要 root 审批后才能激活。</p>}
            </div>

            {/* 验证码 */}
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">验证码</label>
              <div className="flex items-center gap-2">
                <div className="relative flex-1">
                  <Input
                    placeholder="输入验证码"
                    value={captchaCode}
                    onChange={(e) => setCaptchaCode(e.target.value)}
                    maxLength={4}
                    required
                    className="h-11 bg-white/70 tracking-widest"
                  />
                </div>
                {captchaImage && (
                  <div className="relative flex h-11 w-[120px] shrink-0 cursor-pointer items-center justify-center overflow-hidden rounded-xl border bg-white" onClick={fetchCaptcha} title="点击刷新">
                    <Image src={captchaImage} alt="验证码" width={120} height={40} className="h-full w-full object-contain" unoptimized />
                    <div className="absolute inset-0 flex items-center justify-center bg-black/0 transition hover:bg-black/10">
                      <RefreshCw className="size-4 text-transparent transition group-hover:text-gray-500" style={{ textShadow: "0 0 4px rgba(0,0,0,0.3)" }} />
                    </div>
                  </div>
                )}
              </div>
            </div>

            {error && <p className="rounded-xl bg-red-50 p-3 text-sm text-red-600">{error}</p>}
            {success && <p className="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-700">{success}</p>}

            <Button type="submit" className="h-11 w-full bg-slate-950 hover:bg-slate-800" disabled={submitting}>
              {submitting ? "注册中..." : "注册"}
              {!submitting && <ArrowRight className="size-4" />}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            已有账号？{" "}
            <Link href="/login" className="font-semibold text-primary hover:underline">
              登录
            </Link>
          </p>
        </section>
      </main>
    </div>
  );
}
