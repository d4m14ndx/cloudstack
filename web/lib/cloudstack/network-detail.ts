import {
  mockNetworkDetails,
  type Event,
  type Network,
  type NetworkAclList,
  type NetworkAclRule,
  type NetworkDetail,
  type NetworkPublicIp,
  type NetworkTier,
} from "../mock-data.ts";
import { mapCloudStackEventToEvent, type CloudStackEvent, type ListEventsResponse } from "./events.ts";
import {
  mapCloudStackIsolatedNetworkToNetwork,
  mapCloudStackVpcToNetwork,
  type CloudStackNetwork,
  type CloudStackNic,
  type CloudStackVirtualMachine,
  type CloudStackVpc,
  type ListNetworksResponse,
  type ListVirtualMachinesResponse,
  type ListVpcsResponse,
} from "./networks.ts";

type FetchOptions = {
  fetchImpl?: typeof fetch;
  requestHeaders?: Pick<Headers, "get">;
};

type DetailVpc = CloudStackVpc & {
  account?: string;
  domain?: string;
  project?: string;
  vpcofferingname?: string;
  networkdomain?: string;
  redundantvpc?: boolean | string;
  distributedvpc?: boolean | string;
  restartrequired?: boolean | string;
};

type DetailNetwork = CloudStackNetwork & {
  account?: string;
  domain?: string;
  project?: string;
  netmask?: string;
  networkdomain?: string;
  networkofferingname?: string;
  aclid?: string;
  restartrequired?: boolean | string;
  specifyipranges?: boolean | string;
  canusefordeploy?: boolean | string;
};

type CloudStackPublicIp = {
  id?: string;
  ipaddress?: string;
  state?: string;
  issourcenat?: boolean | string;
  isstaticnat?: boolean | string;
  associatednetworkname?: string;
  virtualmachinename?: string;
};

type ListPublicIpAddressesResponse = {
  listpublicipaddressesresponse?: {
    count?: number | string;
    publicipaddress?: CloudStackPublicIp[];
  };
};

type CloudStackAclList = {
  id?: string;
  name?: string;
  description?: string;
};

type ListNetworkAclListsResponse = {
  listnetworkacllistsresponse?: {
    count?: number | string;
    networkacllist?: CloudStackAclList[];
  };
};

type CloudStackAclRule = {
  id?: string;
  aclid?: string;
  number?: string | number;
  action?: string;
  protocol?: string;
  cidrlist?: string;
  startport?: string | number;
  endport?: string | number;
  icmptype?: string | number;
  icmpcode?: string | number;
  traffictype?: string;
  state?: string;
};

type ListNetworkAclsResponse = {
  listnetworkaclsresponse?: {
    count?: number | string;
    networkacl?: CloudStackAclRule[];
  };
};

export async function getNetworkDetailFromBff(
  id: string,
  { fetchImpl = fetch, requestHeaders }: FetchOptions = {},
): Promise<NetworkDetail | null> {
  if (process.env.NEXT_PUBLIC_APP_ENV === "mock" || !process.env.CS_URL) {
    return mockDetailFor(id);
  }

  try {
    const [vpcResponse, networkResponse] = await Promise.all([
      bffFetch(fetchImpl, buildUrl("listVPCs", { id, listall: "true", showicon: "true" }, requestHeaders), requestHeaders),
      bffFetch(
        fetchImpl,
        buildUrl("listNetworks", { id, listall: "true", type: "all", showicon: "true" }, requestHeaders),
        requestHeaders,
      ),
    ]);

    if (!vpcResponse.ok || !networkResponse.ok) {
      return mockDetailFor(id);
    }

    const [vpcPayload, networkPayload] = (await Promise.all([
      vpcResponse.json(),
      networkResponse.json(),
    ])) as [ListVpcsResponse, ListNetworksResponse];

    if (!vpcPayload.listvpcsresponse || !networkPayload.listnetworksresponse) {
      return mockDetailFor(id);
    }

    const vpc = vpcPayload.listvpcsresponse.vpc?.[0] as DetailVpc | undefined;
    if (vpc) {
      return await getVpcDetail(vpc, { fetchImpl, requestHeaders });
    }

    const network = networkPayload.listnetworksresponse.network?.[0] as DetailNetwork | undefined;
    if (network) {
      return await getIsolatedNetworkDetail(network, { fetchImpl, requestHeaders });
    }

    return mockDetailFor(id);
  } catch {
    return mockDetailFor(id);
  }
}

async function getVpcDetail(vpc: DetailVpc, options: Required<Pick<FetchOptions, "fetchImpl">> & FetchOptions): Promise<NetworkDetail | null> {
  const id = vpc.id ?? vpc.name ?? "";
  const { fetchImpl, requestHeaders } = options;

  const [tiersResponse, publicIpsResponse, aclListsResponse, virtualMachinesResponse, eventsResponse] = await Promise.all([
    bffFetch(fetchImpl, buildUrl("listNetworks", { vpcid: id, listall: "true", type: "all" }, requestHeaders), requestHeaders),
    bffFetch(
      fetchImpl,
      buildUrl("listPublicIpAddresses", { vpcid: id, allocatedonly: "true" }, requestHeaders),
      requestHeaders,
    ),
    bffFetch(fetchImpl, buildUrl("listNetworkACLLists", { vpcid: id }, requestHeaders), requestHeaders),
    bffFetch(
      fetchImpl,
      buildUrl("listVirtualMachines", { listall: "true", details: "nics" }, requestHeaders),
      requestHeaders,
    ),
    bffFetch(
      fetchImpl,
      buildUrl("listEvents", { resourceid: id, resourcetype: "Vpc", page: "1", pagesize: "25" }, requestHeaders),
      requestHeaders,
    ),
  ]);

  if (
    !tiersResponse.ok ||
    !publicIpsResponse.ok ||
    !aclListsResponse.ok ||
    !virtualMachinesResponse.ok ||
    !eventsResponse.ok
  ) {
    return mockDetailFor(id);
  }

  const [tiersPayload, publicIpsPayload, aclListsPayload, virtualMachinesPayload, eventsPayload] = (await Promise.all([
    tiersResponse.json(),
    publicIpsResponse.json(),
    aclListsResponse.json(),
    virtualMachinesResponse.json(),
    eventsResponse.json(),
  ])) as [
    ListNetworksResponse,
    ListPublicIpAddressesResponse,
    ListNetworkAclListsResponse,
    ListVirtualMachinesResponse,
    ListEventsResponse,
  ];

  if (
    !tiersPayload.listnetworksresponse ||
    !publicIpsPayload.listpublicipaddressesresponse ||
    !aclListsPayload.listnetworkacllistsresponse ||
    !virtualMachinesPayload.listvirtualmachinesresponse ||
    !eventsPayload.listeventsresponse
  ) {
    return mockDetailFor(id);
  }

  const aclLists = aclListsPayload.listnetworkacllistsresponse.networkacllist ?? [];
  const aclRulePayloads = await fetchVpcAclRules(id, aclLists, { fetchImpl, requestHeaders });
  if (!aclRulePayloads) {
    return mockDetailFor(id);
  }

  const tiers = (tiersPayload.listnetworksresponse.network ?? []).map(mapTier);
  const rules = aclRulePayloads.flatMap((payload) => payload.listnetworkaclsresponse?.networkacl ?? []);
  const instanceCount = countVirtualMachinesByNicValue(
    virtualMachinesPayload.listvirtualmachinesresponse.virtualmachine ?? [],
    "vpcid",
    id,
  );
  const network = mapCloudStackVpcToNetwork(vpc, new Map([[id, instanceCount]]));

  return {
    kind: "VPC",
    network,
    summary: {
      tierCount: tiers.length,
      publicIpCount: publicIpsPayload.listpublicipaddressesresponse.publicipaddress?.length ?? 0,
      aclCount: aclLists.length,
      instanceCount,
    },
    addressing: {
      cidr: vpc.cidr ?? "-",
      gateway: "-",
      netmask: null,
      networkDomain: vpc.networkdomain ?? null,
    },
    ownership: mapOwnership(vpc, network.zone),
    offering: {
      name: vpc.vpcofferingname ?? null,
    },
    flags: {
      redundant: toBoolean(vpc.redundantvpc),
      distributed: toBoolean(vpc.distributedvpc),
      restartRequired: toBoolean(vpc.restartrequired),
      specifyIpRanges: false,
      canUseForDeploy: true,
    },
    tiers,
    publicIps: (publicIpsPayload.listpublicipaddressesresponse.publicipaddress ?? []).map(mapPublicIp),
    aclLists: mapAclLists(aclLists, rules),
    activity: mapEvents(eventsPayload.listeventsresponse.event ?? []),
  };
}

async function getIsolatedNetworkDetail(
  isolatedNetwork: DetailNetwork,
  options: Required<Pick<FetchOptions, "fetchImpl">> & FetchOptions,
): Promise<NetworkDetail | null> {
  const id = isolatedNetwork.id ?? isolatedNetwork.name ?? "";
  const { fetchImpl, requestHeaders } = options;

  const [publicIpsResponse, aclListsResponse, aclRulesResponse, virtualMachinesResponse, eventsResponse] = await Promise.all([
    bffFetch(
      fetchImpl,
      buildUrl("listPublicIpAddresses", { associatednetworkid: id, allocatedonly: "true" }, requestHeaders),
      requestHeaders,
    ),
    bffFetch(fetchImpl, buildUrl("listNetworkACLLists", { networkid: id }, requestHeaders), requestHeaders),
    bffFetch(fetchImpl, buildUrl("listNetworkACLs", { networkid: id }, requestHeaders), requestHeaders),
    bffFetch(
      fetchImpl,
      buildUrl("listVirtualMachines", { listall: "true", details: "nics" }, requestHeaders),
      requestHeaders,
    ),
    bffFetch(
      fetchImpl,
      buildUrl("listEvents", { resourceid: id, resourcetype: "Network", page: "1", pagesize: "25" }, requestHeaders),
      requestHeaders,
    ),
  ]);

  if (
    !publicIpsResponse.ok ||
    !aclListsResponse.ok ||
    !aclRulesResponse.ok ||
    !virtualMachinesResponse.ok ||
    !eventsResponse.ok
  ) {
    return mockDetailFor(id);
  }

  const [publicIpsPayload, aclListsPayload, aclRulesPayload, virtualMachinesPayload, eventsPayload] = (await Promise.all([
    publicIpsResponse.json(),
    aclListsResponse.json(),
    aclRulesResponse.json(),
    virtualMachinesResponse.json(),
    eventsResponse.json(),
  ])) as [
    ListPublicIpAddressesResponse,
    ListNetworkAclListsResponse,
    ListNetworkAclsResponse,
    ListVirtualMachinesResponse,
    ListEventsResponse,
  ];

  if (
    !publicIpsPayload.listpublicipaddressesresponse ||
    !aclListsPayload.listnetworkacllistsresponse ||
    !aclRulesPayload.listnetworkaclsresponse ||
    !virtualMachinesPayload.listvirtualmachinesresponse ||
    !eventsPayload.listeventsresponse
  ) {
    return mockDetailFor(id);
  }

  const instanceCount = countVirtualMachinesByNicValue(
    virtualMachinesPayload.listvirtualmachinesresponse.virtualmachine ?? [],
    "networkid",
    id,
  );
  const network = mapCloudStackIsolatedNetworkToNetwork(isolatedNetwork, new Map([[id, instanceCount]]));
  const aclLists = aclListsPayload.listnetworkacllistsresponse.networkacllist ?? [];
  const rules = aclRulesPayload.listnetworkaclsresponse.networkacl ?? [];

  return {
    kind: "Isolated",
    network,
    summary: {
      tierCount: 0,
      publicIpCount: publicIpsPayload.listpublicipaddressesresponse.publicipaddress?.length ?? 0,
      aclCount: Math.max(aclLists.length, rules.length > 0 ? 1 : 0),
      instanceCount,
    },
    addressing: {
      cidr: isolatedNetwork.networkcidr ?? isolatedNetwork.cidr ?? "-",
      gateway: isolatedNetwork.gateway ?? "-",
      netmask: isolatedNetwork.netmask ?? null,
      networkDomain: isolatedNetwork.networkdomain ?? null,
    },
    ownership: mapOwnership(isolatedNetwork, network.zone),
    offering: {
      name: isolatedNetwork.networkofferingname ?? null,
    },
    flags: {
      redundant: false,
      distributed: false,
      restartRequired: toBoolean(isolatedNetwork.restartrequired),
      specifyIpRanges: toBoolean(isolatedNetwork.specifyipranges),
      canUseForDeploy: toBoolean(isolatedNetwork.canusefordeploy),
    },
    tiers: [],
    publicIps: (publicIpsPayload.listpublicipaddressesresponse.publicipaddress ?? []).map(mapPublicIp),
    aclLists: mapAclLists(aclLists, rules),
    activity: mapEvents(eventsPayload.listeventsresponse.event ?? []),
  };
}

async function fetchVpcAclRules(
  id: string,
  aclLists: CloudStackAclList[],
  { fetchImpl, requestHeaders }: Required<Pick<FetchOptions, "fetchImpl">> & FetchOptions,
): Promise<ListNetworkAclsResponse[] | null> {
  if (aclLists.length === 0) {
    return [];
  }

  const responses = await Promise.all(
    aclLists
      .filter((aclList) => aclList.id)
      .map((aclList) => bffFetch(fetchImpl, buildUrl("listNetworkACLs", { aclid: aclList.id!, }, requestHeaders), requestHeaders)),
  );

  if (responses.some((response) => !response.ok)) {
    return null;
  }

  const payloads = (await Promise.all(responses.map((response) => response.json()))) as ListNetworkAclsResponse[];
  if (payloads.some((payload) => !payload.listnetworkaclsresponse)) {
    return null;
  }

  return payloads.length > 0 ? payloads : [{ listnetworkaclsresponse: { networkacl: [] } }];
}

function mockDetailFor(id: string): NetworkDetail | null {
  return mockNetworkDetails.find((detail) => detail.network.id === id) ?? null;
}

function mapTier(network: CloudStackNetwork): NetworkTier {
  const detail = network as DetailNetwork;
  const id = detail.id ?? detail.name ?? "unknown";

  return {
    id,
    name: detail.name ?? detail.displaytext ?? id,
    cidr: detail.networkcidr ?? detail.cidr ?? "-",
    gateway: detail.gateway ?? "-",
    netmask: detail.netmask ?? null,
    state: mapNetworkState(detail.state),
    offering: detail.networkofferingname ?? null,
    aclId: detail.aclid ?? null,
  };
}

function mapPublicIp(publicIp: CloudStackPublicIp, index: number): NetworkPublicIp {
  const address = publicIp.ipaddress ?? "-";

  return {
    id: publicIp.id ?? address ?? `public-ip-${index}`,
    address,
    state: publicIp.state ?? "-",
    sourceNat: toBoolean(publicIp.issourcenat),
    staticNat: toBoolean(publicIp.isstaticnat),
    networkName: publicIp.associatednetworkname ?? null,
    vmName: publicIp.virtualmachinename ?? null,
  };
}

function mapAclLists(aclLists: CloudStackAclList[], rules: CloudStackAclRule[]): NetworkAclList[] {
  if (aclLists.length === 0 && rules.length > 0) {
    return [
      {
        id: "network-acl",
        name: "Network ACL",
        description: null,
        rules: rules.map(mapAclRule),
      },
    ];
  }

  return aclLists.map((aclList, index) => {
    const id = aclList.id ?? `acl-list-${index}`;
    return {
      id,
      name: aclList.name ?? id,
      description: aclList.description ?? null,
      rules: rules.filter((rule) => !rule.aclid || rule.aclid === aclList.id).map(mapAclRule),
    };
  });
}

function mapAclRule(rule: CloudStackAclRule, index: number): NetworkAclRule {
  const protocol = rule.protocol ?? "all";

  return {
    id: rule.id ?? `acl-rule-${index}`,
    number: String(rule.number ?? "-"),
    action: rule.action ?? "-",
    protocol,
    range: formatRuleRange(rule, protocol),
    source: rule.cidrlist ?? "-",
    trafficType: rule.traffictype ?? "-",
    state: rule.state ?? "-",
  };
}

function mapEvents(events: CloudStackEvent[]): Event[] {
  return events.map(mapCloudStackEventToEvent);
}

function mapOwnership(resource: DetailVpc | DetailNetwork, zone: string): NetworkDetail["ownership"] {
  return {
    account: resource.account ?? "-",
    domain: resource.domain ?? "-",
    project: resource.project ?? null,
    zone,
  };
}

function countVirtualMachinesByNicValue(
  virtualMachines: CloudStackVirtualMachine[],
  field: keyof Pick<CloudStackNic, "networkid" | "vpcid">,
  value: string,
): number {
  const ids = new Set<string>();

  for (const virtualMachine of virtualMachines) {
    if (!virtualMachine.id) {
      continue;
    }

    if ((virtualMachine.nic ?? []).some((nic) => nic[field] === value)) {
      ids.add(virtualMachine.id);
    }
  }

  return ids.size;
}

function mapNetworkState(state: string | undefined): Network["state"] {
  const normalized = state?.trim().toLowerCase() ?? "";
  return ["enabled", "running", "implemented", "allocated", "setup"].includes(normalized) ? "running" : "warning";
}

function formatRuleRange(rule: CloudStackAclRule, protocol: string): string {
  if (protocol.toLowerCase() === "icmp") {
    if (rule.icmptype || rule.icmpcode) {
      return `${rule.icmptype ?? "any"}/${rule.icmpcode ?? "any"}`;
    }
    return "icmp";
  }

  if (rule.startport && rule.endport && String(rule.startport) !== String(rule.endport)) {
    return `${rule.startport}-${rule.endport}`;
  }

  return String(rule.startport ?? rule.endport ?? protocol);
}

function toBoolean(value: boolean | string | undefined): boolean {
  if (typeof value === "boolean") {
    return value;
  }

  return value?.toLowerCase() === "true";
}

function bffFetch(
  fetchImpl: typeof fetch,
  url: string,
  requestHeaders?: Pick<Headers, "get">,
): Promise<Response> {
  return fetchImpl(url, {
    method: "GET",
    cache: "no-store",
    headers: buildForwardedHeaders(requestHeaders),
  });
}

function buildUrl(command: string, params: Record<string, string>, requestHeaders?: Pick<Headers, "get">): string {
  const search = new URLSearchParams(params);
  return `${getRequestOrigin(requestHeaders)}/api/cs/${command}?${search.toString()}`;
}

function getRequestOrigin(requestHeaders?: Pick<Headers, "get">): string {
  if (process.env.NEXTAUTH_URL) {
    return process.env.NEXTAUTH_URL.replace(/\/$/, "");
  }

  const host = requestHeaders?.get("x-forwarded-host") ?? requestHeaders?.get("host") ?? "localhost:3000";
  const protocol = requestHeaders?.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}

function buildForwardedHeaders(requestHeaders?: Pick<Headers, "get">): HeadersInit | undefined {
  const cookie = requestHeaders?.get("cookie");
  return cookie ? { cookie } : undefined;
}
