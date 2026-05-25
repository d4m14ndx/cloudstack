import { BrandMark, BrandWordmark } from "@/components/shell/brand-mark";
import { Button } from "@/components/ui/button";
import { authProvidersConfigured } from "@/lib/env";
import Link from "next/link";

export const metadata = { title: "Sign in" };

type LoginPageProps = {
  searchParams?: {
    error?: string | string[];
  };
};

const authErrorMessages: Record<string, string> = {
  AccessDenied: "Access was denied by the identity provider.",
  Configuration: "Single sign-on is not configured correctly.",
  OAuthSignin: "Single sign-on could not be started.",
  OAuthCallback: "Single sign-on could not be completed.",
};

export default function LoginPage({ searchParams }: LoginPageProps) {
  const authentikReady = authProvidersConfigured.authentik;
  const entraReady = authProvidersConfigured.entraId;
  const authError = readAuthError(searchParams?.error);

  return (
    <main aria-labelledby="login-title" className="grid min-h-screen place-items-center bg-[color:var(--bg)] px-4">
      <section
        aria-describedby="login-description login-provider-status"
        aria-labelledby="login-title"
        className="w-full max-w-sm rounded-[var(--radius-xl)] border border-[color:var(--border)] bg-[color:var(--surface)] p-8 shadow-[var(--shadow-md)]"
      >
        <div className="mb-6 flex items-center gap-2.5">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-[color:var(--accent)] to-[color:var(--accent-hover)] text-white">
            <BrandMark size={22} />
          </div>
          <BrandWordmark className="text-base tracking-tight" />
        </div>
        <h1 id="login-title" className="text-lg font-semibold tracking-[-0.005em]">
          Sign in to CloudStack
        </h1>
        <p id="login-description" className="mt-1 text-sm text-[color:var(--fg-muted)]">
          Continue with single sign-on through your identity provider.
        </p>

        {authError ? (
          <p
            className="mt-4 rounded-md border border-[color:var(--danger)]/30 bg-[color:var(--danger)]/10 px-3 py-2 text-sm text-[color:var(--danger)]"
            role="alert"
          >
            {authError}
          </p>
        ) : null}

        <div aria-label="Identity provider sign-in options" className="mt-6 space-y-2" role="group">
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

        <p id="login-provider-status" className="mt-6 text-xs text-[color:var(--fg-dim)]" role="status">
          Configure an identity provider to enable single sign-on.
        </p>
      </section>
    </main>
  );
}

function readAuthError(error: string | string[] | undefined): string | null {
  const code = Array.isArray(error) ? error[0] : error;

  if (!code) {
    return null;
  }

  return authErrorMessages[code] ?? "Single sign-on failed. Try again or contact your CloudStack administrator.";
}
