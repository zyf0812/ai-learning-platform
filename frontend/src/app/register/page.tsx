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
    <div className="min-h-screen flex bg-gradient-to-br from-slate-50 via-slate-50 to-slate-100">
      <div className="flex-1 flex items-center justify-center p-8">
        <div className="w-full max-w-sm">
          <div className="text-center mb-8">
            <div className="w-12 h-12 rounded-2xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center text-white text-xl font-bold mx-auto mb-4 shadow-lg shadow-blue-500/20">A</div>
            <h1 className="text-xl font-bold text-slate-900">注册 AI 学习平台</h1>
            <p className="text-sm text-slate-500 mt-1.5">创建账号开始学习</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4 bg-white border border-slate-200 rounded-2xl shadow-sm p-6">
            <div>
              <label className="text-sm font-medium text-slate-700 mb-1.5 block">用户名</label>
              <Input placeholder="至少3位" value={username} onChange={(e) => setUsername(e.target.value)} required minLength={3} className="h-10 text-slate-900 border-slate-300" />
            </div>
            <div>
              <label className="text-sm font-medium text-slate-700 mb-1.5 block">密码</label>
              <Input type="password" placeholder="至少6位" value={password} onChange={(e) => setPassword(e.target.value)} required className="h-10 text-slate-900 border-slate-300" />
            </div>
            <div>
              <label className="text-sm font-medium text-slate-700 mb-1.5 block">确认密码</label>
              <Input type="password" placeholder="再输一次" value={confirm} onChange={(e) => setConfirm(e.target.value)} required className="h-10 text-slate-900 border-slate-300" />
            </div>
            {error && <p className="text-red-600 text-sm bg-red-50 p-2.5 rounded-lg">{error}</p>}
            {success && <div className="bg-emerald-50 border border-emerald-200 text-emerald-700 p-3 rounded-xl text-sm">✅ {success}</div>}
            <div>
              <label className="text-sm font-medium text-slate-700 mb-1.5 block">账号类型</label>
              <div className="flex gap-2">
                <label className={`flex-1 text-center text-sm py-2 rounded-xl border cursor-pointer transition-all duration-150 ${role === "user" ? "bg-blue-50 border-blue-300 text-blue-700 font-medium" : "border-slate-200 text-slate-500 hover:bg-slate-50"}`}>
                  <input type="radio" name="role" value="user" className="hidden" checked={role === "user"} onChange={() => setRole("user")} />
                  普通用户
                </label>
                <label className={`flex-1 text-center text-sm py-2 rounded-xl border cursor-pointer transition-all duration-150 ${role === "admin" ? "bg-violet-50 border-violet-300 text-violet-700 font-medium" : "border-slate-200 text-slate-500 hover:bg-slate-50"}`}>
                  <input type="radio" name="role" value="admin" className="hidden" checked={role === "admin"} onChange={() => setRole("admin")} />
                  管理员
                </label>
              </div>
              {role === "admin" && <p className="text-xs text-amber-600 mt-1.5">管理员需要 root 审批后才能激活</p>}
            </div>
            <Button type="submit" className="w-full h-10 bg-black text-white hover:bg-gray-800" disabled={submitting}>
              {submitting ? "注册中..." : "注册"}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-slate-500">
            已有账号？{" "}
            <Link href="/login" className="text-blue-600 font-medium hover:underline">登录</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
