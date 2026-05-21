import type { NextRequest } from "next/server";

import { getAuthenticatedUser } from "@/lib/auth/server";
import { getBffConfig } from "@/lib/bff/config";
import { getBffSessionStore, getOrCreateDevSession } from "@/lib/bff/session-store";
import { CloudStackClient } from "@/lib/cloudstack/client";

import {
  createCloudStackRouteHandlers,
  type CloudStackRouteDeps,
  type CloudStackRouteRequest,
} from "./route-core";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const defaultCloudStackRouteDeps: CloudStackRouteDeps = {
  getConfig: getBffConfig,
  getStore: getBffSessionStore,
  getAuthenticatedUser,
  createClient: (config) =>
    new CloudStackClient({
      baseUrl: config.cloudstackUrl ?? "",
      serviceApiKey: config.serviceApiKey,
      serviceSecretKey: config.serviceSecretKey,
    }),
  getOrCreateDevSession,
};

const handlers = createCloudStackRouteHandlers(defaultCloudStackRouteDeps);

export function GET(request: NextRequest, context: Parameters<typeof handlers.GET>[1]): Promise<Response> {
  return handlers.GET(request as CloudStackRouteRequest, context);
}

export function POST(request: NextRequest, context: Parameters<typeof handlers.POST>[1]): Promise<Response> {
  return handlers.POST(request as CloudStackRouteRequest, context);
}
