import { create } from "zustand";
import { persist } from "zustand/middleware";

export type Theme = "light" | "dark";
export type Density = "compact" | "cozy" | "comfortable";
export type SidebarStyle = "default" | "compact" | "wide";

export const ACCENT_SWATCHES = [
  "#5b5bf5", // indigo (default)
  "#10b981", // emerald
  "#ec4899", // pink
  "#f59e0b", // amber
  "#06b6d4", // cyan
  "#ef4444", // red
] as const;

type TweaksState = {
  theme: Theme;
  density: Density;
  accent: string;
  sidebarStyle: SidebarStyle;
  cmdkOpen: boolean;
  tweaksOpen: boolean;
};

type TweaksActions = {
  setTheme: (t: Theme) => void;
  setDensity: (d: Density) => void;
  setAccent: (a: string) => void;
  setSidebarStyle: (s: SidebarStyle) => void;
  toggleCmdk: () => void;
  setCmdkOpen: (open: boolean) => void;
  toggleTweaks: () => void;
  setTweaksOpen: (open: boolean) => void;
};

export const useTweaks = create<TweaksState & TweaksActions>()(
  persist(
    (set) => ({
      theme: "dark",
      density: "cozy",
      accent: "#5b5bf5",
      sidebarStyle: "default",
      cmdkOpen: false,
      tweaksOpen: false,
      setTheme: (theme) => set({ theme }),
      setDensity: (density) => set({ density }),
      setAccent: (accent) => set({ accent }),
      setSidebarStyle: (sidebarStyle) => set({ sidebarStyle }),
      toggleCmdk: () => set((s) => ({ cmdkOpen: !s.cmdkOpen })),
      setCmdkOpen: (cmdkOpen) => set({ cmdkOpen }),
      toggleTweaks: () => set((s) => ({ tweaksOpen: !s.tweaksOpen })),
      setTweaksOpen: (tweaksOpen) => set({ tweaksOpen }),
    }),
    {
      name: "cloudstack-tweaks",
      partialize: (s) => ({
        theme: s.theme,
        density: s.density,
        accent: s.accent,
        sidebarStyle: s.sidebarStyle,
      }),
    }
  )
);
