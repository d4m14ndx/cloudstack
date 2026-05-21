import assert from "node:assert/strict";
import test from "node:test";

import { mockTemplates } from "../mock-data.ts";
import {
  getTemplatesFromBff,
  mapCloudStackTemplateToTemplate,
  templatesFromListTemplatesResponse,
} from "./templates.ts";

test("mapCloudStackTemplateToTemplate maps CloudStack template fields into the UI template contract", () => {
  const template = mapCloudStackTemplateToTemplate({
    id: "tmpl-1",
    name: "ubuntu-24",
    displaytext: "Ubuntu 24.04 LTS",
    ostypename: "Ubuntu 24.04 LTS (64-bit)",
    size: 2684354560,
    arch: "x86_64",
    isfeatured: true,
    hypervisor: "KVM",
    account: "system",
  });

  assert.deepEqual(template, {
    id: "tmpl-1",
    name: "Ubuntu 24.04 LTS",
    os: "Ubuntu",
    size: "2.5 GB",
    arch: "x86_64",
    featured: true,
    hypervisors: ["KVM"],
    account: "system",
  });
});

test("mapCloudStackTemplateToTemplate falls back through minimal CloudStack fields", () => {
  const template = mapCloudStackTemplateToTemplate({
    id: "tmpl-2",
    name: "arm-template",
    size: "1073741824",
    arch: "aarch64",
    ispublic: true,
  });

  assert.equal(template.id, "tmpl-2");
  assert.equal(template.name, "arm-template");
  assert.equal(template.os, "Unknown");
  assert.equal(template.size, "1 GB");
  assert.equal(template.arch, "arm64");
  assert.equal(template.featured, false);
  assert.deepEqual(template.hypervisors, []);
  assert.equal(template.account, "unknown");
});

test("templatesFromListTemplatesResponse maps the CloudStack response envelope", () => {
  const templates = templatesFromListTemplatesResponse({
    listtemplatesresponse: {
      count: 1,
      template: [
        {
          id: "tmpl-3",
          name: "debian-12",
          ostypename: "Debian GNU/Linux 12 (64-bit)",
          size: 2147483648,
          hypervisor: "KVM",
        },
      ],
    },
  });

  assert.equal(templates.length, 1);
  assert.equal(templates[0]?.name, "debian-12");
  assert.equal(templates[0]?.os, "Debian");
  assert.equal(templates[0]?.size, "2 GB");
});

test("getTemplatesFromBff calls the BFF listTemplates command and forwards cookies", async () => {
  const previousCsUrl = process.env.CS_URL;
  const previousNextAuthUrl = process.env.NEXTAUTH_URL;
  const previousAppEnv = process.env.NEXT_PUBLIC_APP_ENV;
  process.env.CS_URL = "http://cloudstack.local";
  delete process.env.NEXTAUTH_URL;
  delete process.env.NEXT_PUBLIC_APP_ENV;

  let requestedUrl = "";
  let forwardedCookie: string | undefined;
  const fetchImpl: typeof fetch = async (input, init) => {
    requestedUrl = String(input);
    forwardedCookie = (init?.headers as Record<string, string> | undefined)?.cookie;
    return Response.json({
      listtemplatesresponse: {
        template: [{ id: "tmpl-4", name: "api-template", size: 1073741824 }],
      },
    });
  };

  try {
    const templates = await getTemplatesFromBff({
      fetchImpl,
      requestHeaders: new Headers({
        cookie: "cloudstack.session=opaque",
        host: "console.example.test",
        "x-forwarded-proto": "https",
      }),
    });
    const url = new URL(requestedUrl);

    assert.equal(url.origin, "https://console.example.test");
    assert.equal(url.pathname, "/api/cs/listTemplates");
    assert.equal(url.searchParams.get("templatefilter"), "executable");
    assert.equal(url.searchParams.get("details"), "min");
    assert.equal(url.searchParams.get("showunique"), "true");
    assert.equal(forwardedCookie, "cloudstack.session=opaque");
    assert.equal(templates[0]?.name, "api-template");
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
    restoreEnv("NEXTAUTH_URL", previousNextAuthUrl);
    restoreEnv("NEXT_PUBLIC_APP_ENV", previousAppEnv);
  }
});

test("getTemplatesFromBff returns mock templates when CloudStack is unavailable", async () => {
  const previousCsUrl = process.env.CS_URL;
  process.env.CS_URL = "http://cloudstack.local";

  try {
    const templates = await getTemplatesFromBff({
      fetchImpl: async () => new Response("{}", { status: 503 }),
    });

    assert.equal(templates, mockTemplates);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

test("getTemplatesFromBff does not call the BFF when CS_URL is absent", async () => {
  const previousCsUrl = process.env.CS_URL;
  delete process.env.CS_URL;
  let called = false;

  try {
    const templates = await getTemplatesFromBff({
      fetchImpl: async () => {
        called = true;
        return Response.json({});
      },
    });

    assert.equal(called, false);
    assert.equal(templates, mockTemplates);
  } finally {
    restoreEnv("CS_URL", previousCsUrl);
  }
});

function restoreEnv(name: string, value: string | undefined): void {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
