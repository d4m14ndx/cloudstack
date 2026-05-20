import { BrandMark, BrandWordmark } from "@/components/shell/brand-mark";
import { Button } from "@/components/ui/button";
import { authProvidersConfigured } from "@/lib/env";
import Link from "next/link";

export const metadata = { title: "Sign in" };

export default function LoginPage() {
  const authentikReady = authProvidersConfigured.authentik;
  const entraReady = authProvidersConfigured.entraId;

  return (
    <main className="grid min-h-screen place-items-center bg-[color:var(--bg)] px-4">
      <div className="w-full max-w-sm rounded-[var(--radius-xl)] border border-[color:var(--border)] bg-[color:var(--surface)] p-8 shadow-[var(--shadow-md)]">
        <div className="mb-6 flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-[color:var(--accent)] to-[color:var(--accent-hover)] text-white">
            <BrandMark size={22} />
          </div>
          <BrandWordmark className="text-base tracking-tight" />
        </div>
        <h1 className="text-lg font-semibold tracking-[-0.005em]">Sign in to CloudStack</h1>
        <p className="mt-1 text-sm text-[color:var(--fg-muted)]">
          Continue with single sign-on through your identity provider.
        </p>

        <div className="mt-6 space-y-2">
          <Button variant="primary" size="lg" className="w-full" asChild={authentikReady} disabled={!authentikReady}>
            {authentikReady ? (
              <Link href="/api/auth/signin/authentik">Continue with Authentik</Link>
            ) : (
              <span>Continue with Authentik</span>
            )}
          </Button>
          <Button variant="secondary" size="lg" className="w-full" asChild={entraReady} disabled={!entraReady}>
            {entraReady ? (
              <Link href="/api/auth/signin/entra-id">Continue with Microsoft Entra</Link>
            ) : (
              <span>Continue with Microsoft Entra</span>
            )}
          </Button>
        </div>

        <p className="mt-6 text-xs text-[color:var(--fg-dim)]">
          Configure an identity provider to enable single sign-on.
        </p>
      </div>
    </main>
  );
}
