"use client";

import { useEffect } from "react";
import { useTweaks } from "@/lib/store/tweaks";

/**
 * Reactively bind the tweaks store to <html data-*> attributes
 * and to the --accent CSS variable. No-flash on initial paint is
 * handled in app/layout.tsx via the early-script in <head>.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const theme = useTweaks((s) => s.theme);
  const density = useTweaks((s) => s.density);
  const accent = useTweaks((s) => s.accent);
  const sidebarStyle = useTweaks((s) => s.sidebarStyle);

  useEffect(() => {
    const html = document.documentElement;
    html.dataset.theme = theme;
    html.dataset.density = density;
    html.dataset.sidebar = sidebarStyle;
    html.style.setProperty("--accent", accent);
  }, [theme, density, accent, sidebarStyle]);

  return <>{children}</>;
}

/**
 * Inline script that runs BEFORE React hydrates, reading from
 * localStorage and applying theme/density/accent to <html>.
 * Prevents the dark→light flash on first paint.
 */
export function NoFlashScript() {
  const code = `
    (function() {
      try {
        var raw = localStorage.getItem('cloudstack-tweaks');
        var state = raw ? JSON.parse(raw).state : null;
        var html = document.documentElement;
        html.dataset.theme = (state && state.theme) || 'dark';
        html.dataset.density = (state && state.density) || 'cozy';
        html.dataset.sidebar = (state && state.sidebarStyle) || 'default';
        html.style.setProperty('--accent', (state && state.accent) || '#5b5bf5');
      } catch (e) {
        document.documentElement.dataset.theme = 'dark';
      }
    })();
  `;
  return <script dangerouslySetInnerHTML={{ __html: code }} />;
}
