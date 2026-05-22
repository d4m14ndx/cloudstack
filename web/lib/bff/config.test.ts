import assert from "node:assert/strict";
import test from "node:test";

import { getBffConfig } from "./config.ts";

type EnvSnapshot = Partial<Record<string, string>>;

const ENV_KEYS = [
  "NODE_ENV",
  "NEXT_PHASE",
  "NEXT_PUBLIC_APP_ENV",
  "BFF_DEV_SESSION",
] as const;

function snapshotEnv(): EnvSnapshot {
  const snapshot: EnvSnapshot = {};
  for (const key of ENV_KEYS) {
    snapshot[key] = process.env[key];
  }
  return snapshot;
}

function restoreEnv(snapshot: EnvSnapshot): void {
  for (const key of ENV_KEYS) {
    const value = snapshot[key];
    if (value === undefined) {
      delete process.env[key];
    } else {
      // NODE_ENV is a non-writable property under certain Node/Next bundles;
      // use defineProperty so the restore can't fail mid-test.
      Object.defineProperty(process.env, key, {
        value,
        configurable: true,
        enumerable: true,
        writable: true,
      });
    }
  }
}

function setEnv(env: Partial<Record<(typeof ENV_KEYS)[number], string | undefined>>): void {
  for (const [key, value] of Object.entries(env)) {
    if (value === undefined) {
      delete process.env[key];
    } else {
      Object.defineProperty(process.env, key, {
        value,
        configurable: true,
        enumerable: true,
        writable: true,
      });
    }
  }
}

test("allowDevSession is true when BFF_DEV_SESSION=true in development", () => {
  const snap = snapshotEnv();
  try {
    setEnv({ NODE_ENV: "development", NEXT_PHASE: undefined, NEXT_PUBLIC_APP_ENV: undefined, BFF_DEV_SESSION: "true" });
    assert.equal(getBffConfig().allowDevSession, true);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession is true when NEXT_PUBLIC_APP_ENV=dev in development", () => {
  const snap = snapshotEnv();
  try {
    setEnv({ NODE_ENV: "development", NEXT_PHASE: undefined, NEXT_PUBLIC_APP_ENV: "dev", BFF_DEV_SESSION: undefined });
    assert.equal(getBffConfig().allowDevSession, true);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession is false in production even when BFF_DEV_SESSION=true is set", () => {
  const snap = snapshotEnv();
  try {
    setEnv({ NODE_ENV: "production", NEXT_PHASE: undefined, NEXT_PUBLIC_APP_ENV: undefined, BFF_DEV_SESSION: "true" });
    assert.equal(getBffConfig().allowDevSession, false);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession is false in production even when NEXT_PUBLIC_APP_ENV=dev is set", () => {
  const snap = snapshotEnv();
  try {
    setEnv({ NODE_ENV: "production", NEXT_PHASE: undefined, NEXT_PUBLIC_APP_ENV: "dev", BFF_DEV_SESSION: undefined });
    assert.equal(getBffConfig().allowDevSession, false);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession is true during the production build phase when flags are set", () => {
  const snap = snapshotEnv();
  try {
    setEnv({
      NODE_ENV: "production",
      NEXT_PHASE: "phase-production-build",
      NEXT_PUBLIC_APP_ENV: "dev",
      BFF_DEV_SESSION: undefined,
    });
    // The build phase isn't a real runtime — gating it the same way as runtime
    // would cause `next build` against a containerised setup to tree-shake
    // the dev escape branch unexpectedly. So the flag must still flip on here.
    assert.equal(getBffConfig().allowDevSession, true);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession is false when no enabling flag is set", () => {
  const snap = snapshotEnv();
  try {
    setEnv({
      NODE_ENV: "development",
      NEXT_PHASE: undefined,
      NEXT_PUBLIC_APP_ENV: undefined,
      BFF_DEV_SESSION: undefined,
    });
    assert.equal(getBffConfig().allowDevSession, false);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession accepts BFF_DEV_SESSION=1 in addition to 'true'", () => {
  const snap = snapshotEnv();
  try {
    setEnv({ NODE_ENV: "development", NEXT_PHASE: undefined, NEXT_PUBLIC_APP_ENV: undefined, BFF_DEV_SESSION: "1" });
    assert.equal(getBffConfig().allowDevSession, true);
  } finally {
    restoreEnv(snap);
  }
});

test("allowDevSession ignores arbitrary BFF_DEV_SESSION values", () => {
  const snap = snapshotEnv();
  try {
    setEnv({ NODE_ENV: "development", NEXT_PHASE: undefined, NEXT_PUBLIC_APP_ENV: undefined, BFF_DEV_SESSION: "maybe" });
    assert.equal(getBffConfig().allowDevSession, false);
  } finally {
    restoreEnv(snap);
  }
});
