"use client";

import { useState } from "react";
import { useTheme, FONTS, COLOR_PRESETS } from "@/components/theme-context";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";

export function SettingsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { config, setFont, setColor, setMode } = useTheme();
  const [font, setFontLocal] = useState(config.font);
  const [color, setColorLocal] = useState(config.colorScheme);
  const [mode, setModeLocal] = useState(config.mode);

  const colorVars = COLOR_PRESETS[config.colorScheme] || COLOR_PRESETS.blue;

  const fontLabels: Record<string, string> = { default: "系统默认", songti: "宋体", kaiti: "楷体", heiti: "黑体" };
  const colorLabels: Record<string, string> = {
    blue: "天空蓝", indigo: "靛青", green: "翠绿", teal: "墨绿",
    purple: "紫罗兰", rose: "玫瑰红", amber: "琥珀", slate: "岩灰",
  };

  const handleApply = () => {
    setFont(font);
    setColor(color);
    setMode(mode);
    // 先保存到 localStorage，再刷新页面使全站生效
    const newConfig = { font, colorScheme: color, mode };
    localStorage.setItem("theme-config", JSON.stringify(newConfig));
    window.location.reload();
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>个性化设置</DialogTitle></DialogHeader>
        <div className="space-y-5">
          {/* 字体 */}
          <div>
            <p className="text-sm font-medium mb-2">字体</p>
            <div className="grid grid-cols-2 gap-2">
              {Object.entries(fontLabels).map(([k, v]) => (
                <button key={k} onClick={() => setFontLocal(k)}
                  className={`text-sm py-2 rounded-lg border transition-colors ${
                    font === k ? "border-blue-500 bg-blue-50 text-blue-700" : "border-gray-200 hover:bg-gray-50"
                  }`}
                  style={{ fontFamily: FONTS[k] }}>{v}</button>
              ))}
            </div>
          </div>

          {/* 颜色方案 */}
          <div>
            <p className="text-sm font-medium mb-2">颜色方案</p>
            <div className="grid grid-cols-4 gap-2">
              {Object.entries(colorLabels).map(([k, v]) => (
                <button key={k} onClick={() => setColorLocal(k)}
                  className={`text-xs py-1.5 rounded-lg border transition-colors ${
                    color === k ? "ring-2 ring-offset-1 ring-blue-400" : "hover:scale-105"
                  }`}>
                  <div className="w-5 h-5 rounded-full mx-auto mb-0.5" style={{ backgroundColor: (COLOR_PRESETS[k] || COLOR_PRESETS.blue).light.primary }} />
                  <span className="text-[10px]">{v}</span>
                </button>
              ))}
            </div>
          </div>

          {/* 明暗模式 */}
          <div>
            <p className="text-sm font-medium mb-2">显示模式</p>
            <div className="flex gap-2">
              <button onClick={() => setModeLocal("light")}
                className={`flex-1 py-2 rounded-lg border text-sm ${mode === "light" ? "border-amber-400 bg-amber-50" : "border-gray-200"}`}>
                ☀️ 浅色
              </button>
              <button onClick={() => setModeLocal("dark")}
                className={`flex-1 py-2 rounded-lg border text-sm ${mode === "dark" ? "border-indigo-400 bg-indigo-50" : "border-gray-200"}`}>
                🌙 深色
              </button>
            </div>
          </div>

          {/* 确认按钮 */}
          <Button className="w-full" onClick={handleApply}>确认设置</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
