import { BrandMark, BrandWordmark } from "@/components/shell/brand-mark";
import { Button } from "@/components/ui/button";

export const metadata = { title: "Sign in" };

export default function LoginPage() {
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
          <Button variant="primary" size="lg" className="w-full" disabled>
            Continue with Authentik
          </Button>
          <Button variant="secondary" size="lg" className="w-full" disabled>
            Continue with Microsoft Entra
          </Button>
        </div>

        <p className="mt-6 text-xs text-[color:var(--fg-dim)]">
          Phase 5a scaffold — auth wiring lands in Phase 5b.
        </p>
      </div>
    </main>
  );
}
