import NextAuth, { type NextAuthConfig } from "next-auth";
import Authentik from "next-auth/providers/authentik";
import MicrosoftEntraID from "next-auth/providers/microsoft-entra-id";
import type { Provider } from "next-auth/providers";
import { currentUserFromClaims } from "@/lib/auth/claims";
import { RedisAuthAdapter } from "@/lib/auth/redis-adapter";
import { getRedis } from "@/lib/auth/redis";
import { authProvidersConfigured, isAuthConfigured, webEnv } from "@/lib/env";

function providers(): Provider[] {
  const configured: Provider[] = [];

  if (
    authProvidersConfigured.authentik &&
    webEnv.AUTHENTIK_ISSUER &&
    webEnv.AUTHENTIK_CLIENT_ID &&
    webEnv.AUTHENTIK_CLIENT_SECRET
  ) {
    configured.push(
      Authentik({
        clientId: webEnv.AUTHENTIK_CLIENT_ID,
        clientSecret: webEnv.AUTHENTIK_CLIENT_SECRET,
        issuer: webEnv.AUTHENTIK_ISSUER.replace(/\/$/, ""),
        profile(profile) {
          return currentUserFromClaims(profile);
        },
      })
    );
  }

  if (
    authProvidersConfigured.entraId &&
    webEnv.AUTH_MICROSOFT_ENTRA_ID_ID &&
    webEnv.AUTH_MICROSOFT_ENTRA_ID_SECRET
  ) {
    const entraId = MicrosoftEntraID({
      clientId: webEnv.AUTH_MICROSOFT_ENTRA_ID_ID,
      clientSecret: webEnv.AUTH_MICROSOFT_ENTRA_ID_SECRET,
      issuer: webEnv.AUTH_MICROSOFT_ENTRA_ID_ISSUER,
      profile(profile) {
        return currentUserFromClaims(profile as unknown as Record<string, unknown>);
      },
    });

    configured.push({
      ...entraId,
      id: "entra-id",
      name: "Microsoft Entra",
    });
  }

  return configured;
}

export const authConfig = {
  providers: providers(),
  secret: webEnv.NEXTAUTH_SECRET,
  trustHost: true,
  session: {
    strategy: isAuthConfigured ? "database" : "jwt",
    maxAge: webEnv.BFF_SESSION_TTL_SECONDS,
  },
  adapter: isAuthConfigured ? RedisAuthAdapter(getRedis()) : undefined,
  pages: {
    signIn: "/login",
  },
  callbacks: {
    session({ session, user }) {
      if (user) {
        session.user = user as typeof session.user;
      }
      return session;
    },
  },
} satisfies NextAuthConfig;

export const { handlers, auth, signIn, signOut } = NextAuth(authConfig);
