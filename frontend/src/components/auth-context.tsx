"use client";

import {
  createContext, useContext, useState, useEffect, useCallback, useRef, type ReactNode,
} from "react";
import { api } from "@/lib/api";

interface User { id: string; username: string; role?: string; }

interface AuthCtx {
  user: User | null;
  loading: boolean;
  login: (u: string, p: string) => Promise<void>;
  register: (u: string, p: string, r?: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthCtx>(null!);

// 2小时无操作自动登出
const TIMEOUT_MS = 2 * 60 * 60 * 1000;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const lastActivity = useRef(Date.now());
  const timeoutTimer = useRef<any>(null);

  const doLogout = useCallback(() => {
    localStorage.removeItem("token");
    localStorage.setItem("session_expired", "1");
    setUser(null);
  }, []);

  // 检查超时
  const checkTimeout = useCallback(() => {
    if (!user) return;
    const elapsed = Date.now() - lastActivity.current;
    if (elapsed > TIMEOUT_MS) {
      doLogout();
    }
  }, [user, doLogout]);

  // 更新活动时间
  const updateActivity = useCallback(() => {
    lastActivity.current = Date.now();
  }, []);

  useEffect(() => {
    const token = localStorage.getItem("token");
    if (!token) { setLoading(false); return; }
    api.me().then(d => setUser(d.user)).catch(() => localStorage.removeItem("token")).finally(() => setLoading(false));
  }, []);

  // 监听用户活动
  useEffect(() => {
    if (!user) return;
    const events = ["mousedown", "keydown", "scroll", "touchstart"];
    events.forEach(e => window.addEventListener(e, updateActivity));
    // 每分钟检查一次
    timeoutTimer.current = setInterval(checkTimeout, 60_000);
    return () => {
      events.forEach(e => window.removeEventListener(e, updateActivity));
      if (timeoutTimer.current) clearInterval(timeoutTimer.current);
    };
  }, [user, checkTimeout, updateActivity]);

  const login = useCallback(async (username: string, password: string) => {
    const data = await api.login({ username, password });
    localStorage.setItem("token", data.token);
    localStorage.removeItem("session_expired");
    lastActivity.current = Date.now();
    setUser(data.user);
  }, []);

  const register = useCallback(async (username: string, password: string, role?: string) => {
    const data = await api.register({ username, password, role: role || "user" });
    localStorage.setItem("token", data.token);
    lastActivity.current = Date.now();
    setUser(data.user);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem("token");
    lastActivity.current = 0;
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
