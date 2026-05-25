import { createDevBffSession, type BffSession } from "./session";
import { getRedis } from "@/lib/auth/redis";
import { isAuthConfigured } from "@/lib/env";
import type Redis from "ioredis";

export type BffSessionStore = {
  get(sessionId: string): Promise<BffSession | null>;
  set(sessionId: string, session: BffSession, ttlSeconds: number): Promise<void>;
  delete(sessionId: string): Promise<void>;
};

type StoredSession = {
  session: BffSession;
  expiresAt: number;
};

declare global {
  // TODO(Phase 5b auth): replace the in-memory dev store with Redis once the
  // auth/package slice lands ioredis and the Auth.js callbacks own persistence.
  // eslint-disable-next-line no-var
  var __cloudstackBffDevSessions: Map<string, StoredSession> | undefined;
}

export class InMemoryBffSessionStore implements BffSessionStore {
  private readonly sessions: Map<string, StoredSession>;

  public constructor() {
    globalThis.__cloudstackBffDevSessions ??= new Map<string, StoredSession>();
    this.sessions = globalThis.__cloudstackBffDevSessions;
  }

  public async get(sessionId: string): Promise<BffSession | null> {
    const stored = this.sessions.get(sessionId);
    if (!stored) {
      return null;
    }

    if (stored.expiresAt <= Date.now()) {
      this.sessions.delete(sessionId);
      return null;
    }

    return stored.session;
  }

  public async set(sessionId: string, session: BffSession, ttlSeconds: number): Promise<void> {
    this.sessions.set(sessionId, {
      session,
      expiresAt: Date.now() + ttlSeconds * 1_000,
    });
  }

  public async delete(sessionId: string): Promise<void> {
    this.sessions.delete(sessionId);
  }
}

export class RedisBffSessionStore implements BffSessionStore {
  public constructor(private readonly redis: Redis) {}

  public async get(sessionId: string): Promise<BffSession | null> {
    const stored = await this.redis.get(this.key(sessionId));
    if (!stored) {
      return null;
    }

    return JSON.parse(stored) as BffSession;
  }

  public async set(sessionId: string, session: BffSession, ttlSeconds: number): Promise<void> {
    await this.redis.set(this.key(sessionId), JSON.stringify(session), "EX", ttlSeconds);
  }

  public async delete(sessionId: string): Promise<void> {
    await this.redis.del(this.key(sessionId));
  }

  private key(sessionId: string): string {
    return `bff:session:${sessionId}`;
  }
}

export function getBffSessionStore(): BffSessionStore {
  if (isAuthConfigured) {
    return new RedisBffSessionStore(getRedis());
  }

  return new InMemoryBffSessionStore();
}

export async function getOrCreateDevSession(
  store: BffSessionStore,
  sessionId: string,
  ttlSeconds: number,
): Promise<BffSession> {
  const existing = await store.get(sessionId);
  if (existing) {
    return existing;
  }

  const session = createDevBffSession();
  await store.set(sessionId, session, ttlSeconds);
  return session;
}
