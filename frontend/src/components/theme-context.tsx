"use client";

import { createContext, useContext, useState, type ReactNode } from "react";

export interface ThemeConfig {
  font: string;
  colorScheme: string;
  mode: "light" | "dark";
}

const FONTS: Record<string, string> = {
  default: "var(--font-geist-sans), system-ui, sans-serif",
  songti: "'Noto Serif SC', 'SimSun', serif",
  kaiti: "'ZCOOL KuaiLe', 'KaiTi', cursive",
  heiti: "'Noto Sans SC', 'Microsoft YaHei', sans-serif",
};

const COLOR_PRESETS: Record<string, { light: Record<string, string>; dark: Record<string, string> }> = {
  blue:    { light: { primary: "#3b82f6" }, dark: { primary: "#60a5fa" } },
  green:   { light: { primary: "#059669" }, dark: { primary: "#34d399" } },
  teal:    { light: { primary: "#0d9488" }, dark: { primary: "#2dd4bf" } },
  purple:  { light: { primary: "#7c3aed" }, dark: { primary: "#a78bfa" } },
  rose:    { light: { primary: "#e11d48" }, dark: { primary: "#fb7185" } },
  amber:   { light: { primary: "#d97706" }, dark: { primary: "#fbbf24" } },
  slate:   { light: { primary: "#475569" }, dark: { primary: "#94a3b8" } },
};

const DEFAULT: ThemeConfig = { font: "default", colorScheme: "blue", mode: "light" };

const ThemeContext = createContext<{
  config: ThemeConfig;
  setFont: (f: string) => void;
  setColor: (c: string) => void;
  setMode: (m: "light" | "dark") => void;
}>(null!);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<ThemeConfig>(() => {
    if (typeof window === "undefined") return DEFAULT;
    try {
      const saved = localStorage.getItem("theme-config");
      const c = saved ? JSON.parse(saved) : DEFAULT;
      applyMode(c.mode);
      applyColors(c.colorScheme, c.mode);
      return c;
    } catch { return DEFAULT; }
  });

  return (
    <ThemeContext.Provider value={{
      config,
      setFont: (f) => save({ ...config, font: f }),
      setColor: (cs) => save({ ...config, colorScheme: cs }),
      setMode: (m) => save({ ...config, mode: m }),
    }}>
      {children}
    </ThemeContext.Provider>
  );

  function save(c: ThemeConfig) {
    setConfig(c);
    localStorage.setItem("theme-config", JSON.stringify(c));
    applyMode(c.mode);
    applyColors(c.colorScheme, c.mode);
  }
}

function applyMode(mode: string) {
  if (typeof window === "undefined") return;
  if (mode === "dark") document.documentElement.classList.add("dark");
  else document.documentElement.classList.remove("dark");
}

function applyColors(scheme: string, mode: "light" | "dark") {
  if (typeof window === "undefined") return;
  const colors = COLOR_PRESETS[scheme]?.[mode];
  if (!colors) return;
  const root = document.documentElement;
  Object.entries(colors).forEach(([k, v]) => {
    root.style.setProperty(`--${k}`, v);
  });
  root.style.setProperty("--primary-foreground", "oklch(0.985 0 0)");
}

export const useTheme = () => useContext(ThemeContext);
export { FONTS, COLOR_PRESETS };
