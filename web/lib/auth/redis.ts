import "server-only";

import Redis from "ioredis";
import { redisUrl } from "@/lib/env";

let redis: Redis | null = null;

export function getRedis(): Redis {
  redis ??= new Redis(redisUrl, {
    lazyConnect: true,
    maxRetriesPerRequest: 2,
  });
  return redis;
}
