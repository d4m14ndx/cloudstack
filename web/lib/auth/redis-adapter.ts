import "server-only";

import type {
  Adapter,
  AdapterAccount,
  AdapterSession,
  AdapterUser,
} from "@auth/core/adapters";
import type Redis from "ioredis";
import { webEnv } from "@/lib/env";

const key = {
  user: (id: string) => `auth:user:${id}`,
  userEmail: (email: string) => `auth:user:email:${email.toLowerCase()}`,
  account: (provider: string, providerAccountId: string) =>
    `auth:account:${provider}:${providerAccountId}`,
  session: (sessionToken: string) => `auth:session:${sessionToken}`,
};

async function readJson<T>(redis: Redis, redisKey: string): Promise<T | null> {
  const value = await redis.get(redisKey);
  return value ? (JSON.parse(value) as T) : null;
}

async function writeJson(redis: Redis, redisKey: string, value: unknown): Promise<void> {
  await redis.set(redisKey, JSON.stringify(value));
}

function sessionTtlSeconds(expires: Date): number {
  const ttl = Math.ceil((expires.getTime() - Date.now()) / 1000);
  return Math.max(1, Math.min(ttl, webEnv.BFF_SESSION_TTL_SECONDS));
}

export function RedisAuthAdapter(redis: Redis): Adapter {
  return {
    async createUser(user) {
      await writeJson(redis, key.user(user.id), user);
      if (user.email) {
        await redis.set(key.userEmail(user.email), user.id);
      }
      return user;
    },

    async getUser(id) {
      return readJson<AdapterUser>(redis, key.user(id));
    },

    async getUserByEmail(email) {
      const id = await redis.get(key.userEmail(email));
      return id ? readJson<AdapterUser>(redis, key.user(id)) : null;
    },

    async getUserByAccount({ provider, providerAccountId }) {
      const account = await readJson<AdapterAccount>(
        redis,
        key.account(provider, providerAccountId)
      );
      return account ? readJson<AdapterUser>(redis, key.user(account.userId)) : null;
    },

    async updateUser(user) {
      const existing = await readJson<AdapterUser>(redis, key.user(user.id));
      if (!existing) {
        throw new Error(`Cannot update missing Auth.js user ${user.id}`);
      }
      const next = { ...existing, ...user };
      await writeJson(redis, key.user(next.id), next);
      if (next.email) {
        await redis.set(key.userEmail(next.email), next.id);
      }
      return next;
    },

    async linkAccount(account) {
      await writeJson(
        redis,
        key.account(account.provider, account.providerAccountId),
        account
      );
      return account;
    },

    async getAccount(providerAccountId, provider) {
      return readJson<AdapterAccount>(redis, key.account(provider, providerAccountId));
    },

    async createSession(session) {
      await redis.set(
        key.session(session.sessionToken),
        JSON.stringify(session),
        "EX",
        sessionTtlSeconds(session.expires)
      );
      return session;
    },

    async getSessionAndUser(sessionToken) {
      const session = await readJson<AdapterSession>(redis, key.session(sessionToken));
      if (!session) return null;

      session.expires = new Date(session.expires);
      const user = await readJson<AdapterUser>(redis, key.user(session.userId));
      return user ? { session, user } : null;
    },

    async updateSession(session) {
      const existing = await readJson<AdapterSession>(
        redis,
        key.session(session.sessionToken)
      );
      if (!existing) return null;

      const next = {
        ...existing,
        ...session,
        expires: session.expires ? new Date(session.expires) : new Date(existing.expires),
      };
      await redis.set(
        key.session(next.sessionToken),
        JSON.stringify(next),
        "EX",
        sessionTtlSeconds(next.expires)
      );
      return next;
    },

    async deleteSession(sessionToken) {
      const existing = await readJson<AdapterSession>(redis, key.session(sessionToken));
      await redis.del(key.session(sessionToken));
      return existing;
    },
  };
}
