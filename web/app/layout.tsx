import type { Metadata } from "next";
import { GeistSans } from "geist/font/sans";
import { GeistMono } from "geist/font/mono";
import { ThemeProvider, NoFlashScript } from "@/components/theme-provider";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    template: "%s — CloudStack",
    default: "CloudStack",
  },
  description: "Modern infrastructure console for Apache CloudStack",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html
      lang="en"
      suppressHydrationWarning
      className={`${GeistSans.variable} ${GeistMono.variable}`}
    >
      <head>
        <NoFlashScript />
      </head>
      <body>
        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  );
}
