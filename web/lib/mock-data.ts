export type ResourceState = "running" | "stopped" | "starting" | "ready" | "detaching" | "error" | "warning";
export type ZoneState = "enabled" | "maintenance";
export type HostState = "up" | "maintenance" | "alert";
export type AccountState = "active" | "disabled";
export type EventLevel = "info" | "warn" | "error";
export type KubernetesState = "running" | "updating" | "degraded";

export type Instance = {
  id: string;
  name: string;
  template: string;
  offering: string;
  cpu: number;
  ram: number;
  state: Extract<ResourceState, "running" | "stopped" | "starting" | "error">;
  ip: string;
  publicIp: string | null;
  zone: string;
  network: string;
  uptime: string | null;
  account: string;
  cpuUsage: number;
  memUsage: number;
};

export type Network = {
  id: string;
  name: string;
  cidr: string;
  type: "VPC" | "Isolated";
  zone: string;
  instances: number;
  state: Extract<ResourceState, "running" | "warning">;
  gateway: string;
};

export type Zone = {
  id: string;
  name: string;
  region: string;
  state: ZoneState;
  hosts: number;
  pods: number;
  instances: number;
  cpuPct: number;
  memPct: number;
  storagePct: number;
};

export type Host = {
  id: string;
  name: string;
  zone: string;
  cluster: string;
  state: HostState;
  cpu: number;
  mem: number;
  instances: number;
  hypervisor: "KVM" | "VMware" | "Hyper-V" | "XenServer";
};

export type Event = {
  timestamp: string;
  level: EventLevel;
  user: string;
  action: string;
  target: string;
  description: string;
};

export type Account = {
  name: string;
  domain: string;
  role: "Admin" | "Domain admin" | "User" | "Service";
  users: number;
  instances: number;
  state: AccountState;
};

export type Volume = {
  id: string;
  name: string;
  sizeGiB: number;
  type: "SSD" | "NVMe" | "Cold";
  attachedTo: string | null;
  zone: string;
  state: Extract<ResourceState, "ready" | "detaching">;
};

export type Template = {
  id: string;
  name: string;
  os: string;
  size: string;
  arch: "x86_64" | "arm64";
  featured: boolean;
  hypervisors: string[];
  account: string;
};

export type KubernetesCluster = {
  id: string;
  name: string;
  version: string;
  zone: string;
  account: string;
  nodes: number;
  state: KubernetesState;
  endpoint: string;
};

export type DashboardMetric = {
  label: string;
  value: string;
  denom: string;
  suffix?: string;
  delta: string;
  series: number[];
  color: string;
};

export const mockInstances: Instance[] = [
  { id: "i-9f3a2b", name: "web-prod-01", template: "Ubuntu 22.04 LTS", offering: "Compute-M", cpu: 4, ram: 8, state: "running", ip: "10.1.0.14", publicIp: "203.0.113.41", zone: "syd-1", network: "prod-vpc", uptime: "47d 3h", account: "platform", cpuUsage: 38, memUsage: 62 },
  { id: "i-3d1c8e", name: "web-prod-02", template: "Ubuntu 22.04 LTS", offering: "Compute-M", cpu: 4, ram: 8, state: "running", ip: "10.1.0.15", publicIp: "203.0.113.42", zone: "syd-1", network: "prod-vpc", uptime: "47d 3h", account: "platform", cpuUsage: 41, memUsage: 58 },
  { id: "i-7b2f4d", name: "db-primary", template: "PostgreSQL 16", offering: "Database-XL", cpu: 16, ram: 64, state: "running", ip: "10.1.1.21", publicIp: null, zone: "syd-1", network: "prod-vpc", uptime: "112d 18h", account: "platform", cpuUsage: 71, memUsage: 84 },
  { id: "i-8c5e9a", name: "db-replica", template: "PostgreSQL 16", offering: "Database-L", cpu: 8, ram: 32, state: "running", ip: "10.1.1.22", publicIp: null, zone: "syd-2", network: "prod-vpc", uptime: "112d 18h", account: "platform", cpuUsage: 22, memUsage: 79 },
  { id: "i-2a6b1f", name: "cache-01", template: "Redis 7", offering: "Memory-M", cpu: 4, ram: 16, state: "running", ip: "10.1.2.10", publicIp: null, zone: "syd-1", network: "prod-vpc", uptime: "29d 6h", account: "platform", cpuUsage: 12, memUsage: 44 },
  { id: "i-1e9d4c", name: "worker-01", template: "Debian 12", offering: "Compute-S", cpu: 2, ram: 4, state: "stopped", ip: "10.1.3.11", publicIp: null, zone: "syd-1", network: "prod-vpc", uptime: null, account: "data-team", cpuUsage: 0, memUsage: 0 },
  { id: "i-5f2a8b", name: "worker-02", template: "Debian 12", offering: "Compute-S", cpu: 2, ram: 4, state: "stopped", ip: "10.1.3.12", publicIp: null, zone: "syd-1", network: "prod-vpc", uptime: null, account: "data-team", cpuUsage: 0, memUsage: 0 },
  { id: "i-6e3a9c", name: "build-runner", template: "Ubuntu 24.04 LTS", offering: "Compute-XL", cpu: 16, ram: 32, state: "running", ip: "10.2.0.5", publicIp: "203.0.113.99", zone: "mel-1", network: "ci-vpc", uptime: "4d 11h", account: "engineering", cpuUsage: 91, memUsage: 53 },
  { id: "i-4d7c1e", name: "staging-app", template: "Ubuntu 22.04 LTS", offering: "Compute-M", cpu: 4, ram: 8, state: "starting", ip: "10.3.0.4", publicIp: null, zone: "syd-1", network: "staging-vpc", uptime: null, account: "engineering", cpuUsage: 0, memUsage: 0 },
  { id: "i-8a2b3f", name: "grafana", template: "Alpine 3.20", offering: "Compute-S", cpu: 2, ram: 4, state: "running", ip: "10.4.0.7", publicIp: "203.0.113.55", zone: "mel-1", network: "obs-vpc", uptime: "62d 4h", account: "platform", cpuUsage: 8, memUsage: 31 },
  { id: "i-3f9e4a", name: "prometheus", template: "Alpine 3.20", offering: "Memory-L", cpu: 8, ram: 32, state: "running", ip: "10.4.0.8", publicIp: null, zone: "mel-1", network: "obs-vpc", uptime: "62d 4h", account: "platform", cpuUsage: 24, memUsage: 67 },
  { id: "i-9c8d2e", name: "jumphost", template: "Debian 12", offering: "Compute-XS", cpu: 1, ram: 2, state: "running", ip: "10.0.0.10", publicIp: "203.0.113.5", zone: "syd-1", network: "mgmt", uptime: "201d 0h", account: "platform", cpuUsage: 3, memUsage: 18 },
  { id: "i-7a1d6e", name: "exp-llama", template: "Ubuntu 24.04 + CUDA", offering: "GPU-A100", cpu: 16, ram: 128, state: "error", ip: "10.5.0.3", publicIp: null, zone: "syd-2", network: "ml-vpc", uptime: null, account: "research", cpuUsage: 0, memUsage: 0 },
];

export const mockNetworks: Network[] = [
  { id: "n-101", name: "prod-vpc", cidr: "10.1.0.0/16", type: "VPC", zone: "syd-1", instances: 6, state: "running", gateway: "10.1.0.1" },
  { id: "n-102", name: "ci-vpc", cidr: "10.2.0.0/16", type: "VPC", zone: "mel-1", instances: 1, state: "running", gateway: "10.2.0.1" },
  { id: "n-103", name: "staging-vpc", cidr: "10.3.0.0/16", type: "VPC", zone: "syd-1", instances: 1, state: "running", gateway: "10.3.0.1" },
  { id: "n-104", name: "obs-vpc", cidr: "10.4.0.0/16", type: "VPC", zone: "mel-1", instances: 2, state: "running", gateway: "10.4.0.1" },
  { id: "n-105", name: "ml-vpc", cidr: "10.5.0.0/16", type: "VPC", zone: "syd-2", instances: 1, state: "warning", gateway: "10.5.0.1" },
  { id: "n-106", name: "mgmt", cidr: "10.0.0.0/24", type: "Isolated", zone: "syd-1", instances: 1, state: "running", gateway: "10.0.0.1" },
];

export const mockZones: Zone[] = [
  { id: "z-syd-1", name: "syd-1", region: "Sydney, AU", state: "enabled", hosts: 24, pods: 4, instances: 218, cpuPct: 64, memPct: 71, storagePct: 58 },
  { id: "z-syd-2", name: "syd-2", region: "Sydney, AU", state: "enabled", hosts: 18, pods: 3, instances: 142, cpuPct: 48, memPct: 53, storagePct: 41 },
  { id: "z-mel-1", name: "mel-1", region: "Melbourne, AU", state: "enabled", hosts: 12, pods: 2, instances: 96, cpuPct: 39, memPct: 44, storagePct: 32 },
  { id: "z-akl-1", name: "akl-1", region: "Auckland, NZ", state: "maintenance", hosts: 6, pods: 1, instances: 28, cpuPct: 12, memPct: 18, storagePct: 9 },
];

export const mockHosts: Host[] = [
  { id: "h-001", name: "hyp-syd1-01", zone: "syd-1", cluster: "cluster-a", state: "up", cpu: 78, mem: 82, instances: 12, hypervisor: "KVM" },
  { id: "h-002", name: "hyp-syd1-02", zone: "syd-1", cluster: "cluster-a", state: "up", cpu: 64, mem: 71, instances: 10, hypervisor: "KVM" },
  { id: "h-003", name: "hyp-syd1-03", zone: "syd-1", cluster: "cluster-a", state: "up", cpu: 41, mem: 58, instances: 8, hypervisor: "KVM" },
  { id: "h-004", name: "hyp-syd1-04", zone: "syd-1", cluster: "cluster-b", state: "up", cpu: 55, mem: 49, instances: 11, hypervisor: "KVM" },
  { id: "h-005", name: "hyp-syd2-01", zone: "syd-2", cluster: "cluster-c", state: "up", cpu: 22, mem: 35, instances: 5, hypervisor: "KVM" },
  { id: "h-006", name: "hyp-mel1-01", zone: "mel-1", cluster: "cluster-d", state: "up", cpu: 89, mem: 91, instances: 14, hypervisor: "KVM" },
  { id: "h-007", name: "hyp-akl1-01", zone: "akl-1", cluster: "cluster-e", state: "maintenance", cpu: 0, mem: 0, instances: 0, hypervisor: "VMware" },
  { id: "h-008", name: "hyp-syd1-05", zone: "syd-1", cluster: "cluster-b", state: "alert", cpu: 96, mem: 88, instances: 9, hypervisor: "KVM" },
];

export const mockEvents: Event[] = [
  { timestamp: "2026-05-16 09:42:11", level: "info", user: "alex.kim", action: "VM.START", target: "i-4d7c1e (staging-app)", description: "Started VM in zone syd-1" },
  { timestamp: "2026-05-16 09:41:02", level: "info", user: "alex.kim", action: "VM.CREATE", target: "i-4d7c1e (staging-app)", description: "Created VM with Compute-M offering" },
  { timestamp: "2026-05-16 09:38:45", level: "warn", user: "system", action: "HOST.ALERT", target: "hyp-syd1-05", description: "CPU usage exceeded 95% for 5 minutes" },
  { timestamp: "2026-05-16 09:22:18", level: "info", user: "morgan.chen", action: "TEMPLATE.REGISTER", target: "Ubuntu 24.04 + CUDA", description: "Registered template in zone syd-2" },
  { timestamp: "2026-05-16 09:15:33", level: "error", user: "system", action: "VM.ERROR", target: "i-7a1d6e (exp-llama)", description: "Failed to allocate GPU resources" },
  { timestamp: "2026-05-16 09:02:01", level: "info", user: "morgan.chen", action: "VOLUME.CREATE", target: "v-2c8d (data-warehouse)", description: "Created 8 TiB SSD volume" },
  { timestamp: "2026-05-16 08:47:55", level: "info", user: "system", action: "SNAPSHOT.CREATE", target: "v-7b2f (db-primary-data)", description: "Scheduled snapshot completed" },
  { timestamp: "2026-05-16 08:32:22", level: "info", user: "alex.kim", action: "NETWORK.UPDATE", target: "ml-vpc", description: "Updated egress rules" },
  { timestamp: "2026-05-16 08:14:09", level: "warn", user: "system", action: "NETWORK.WARN", target: "ml-vpc", description: "Egress traffic high; auto-throttle armed" },
  { timestamp: "2026-05-16 07:55:40", level: "info", user: "sam.rao", action: "ACCOUNT.LOGIN", target: "sam.rao", description: "Login from 203.0.113.18 (Melbourne, AU)" },
  { timestamp: "2026-05-16 07:48:13", level: "info", user: "system", action: "BACKUP.RUN", target: "backups-archive", description: "Nightly backup completed in 2h 14m" },
  { timestamp: "2026-05-16 07:30:00", level: "info", user: "system", action: "AUTOSCALE.UP", target: "web-prod cluster", description: "Scaled out from 2 to 3 instances" },
];

export const mockAccounts: Account[] = [
  { name: "platform", domain: "root", role: "Admin", users: 4, instances: 7, state: "active" },
  { name: "engineering", domain: "root/eng", role: "Domain admin", users: 14, instances: 3, state: "active" },
  { name: "data-team", domain: "root/eng", role: "User", users: 6, instances: 2, state: "active" },
  { name: "research", domain: "root/labs", role: "User", users: 3, instances: 1, state: "active" },
  { name: "qa-bots", domain: "root/eng", role: "Service", users: 1, instances: 0, state: "disabled" },
];

export const mockVolumes: Volume[] = [
  { id: "v-9d3a", name: "web-prod-01-root", sizeGiB: 40, type: "SSD", attachedTo: "web-prod-01", zone: "syd-1", state: "ready" },
  { id: "v-3e1c", name: "web-prod-02-root", sizeGiB: 40, type: "SSD", attachedTo: "web-prod-02", zone: "syd-1", state: "ready" },
  { id: "v-7b2f", name: "db-primary-data", sizeGiB: 1024, type: "NVMe", attachedTo: "db-primary", zone: "syd-1", state: "ready" },
  { id: "v-8c5e", name: "db-replica-data", sizeGiB: 1024, type: "NVMe", attachedTo: "db-replica", zone: "syd-2", state: "ready" },
  { id: "v-1a4b", name: "backups-archive", sizeGiB: 4096, type: "Cold", attachedTo: null, zone: "syd-1", state: "ready" },
  { id: "v-2c8d", name: "data-warehouse", sizeGiB: 8192, type: "SSD", attachedTo: "build-runner", zone: "mel-1", state: "ready" },
  { id: "v-5f9e", name: "ml-checkpoints", sizeGiB: 2048, type: "NVMe", attachedTo: null, zone: "syd-2", state: "detaching" },
];

export const mockTemplates: Template[] = [
  { id: "t-001", name: "Ubuntu 22.04 LTS", os: "Ubuntu", size: "2.4 GB", arch: "x86_64", featured: true, hypervisors: ["KVM", "VMware"], account: "system" },
  { id: "t-002", name: "Ubuntu 24.04 LTS", os: "Ubuntu", size: "2.8 GB", arch: "x86_64", featured: true, hypervisors: ["KVM"], account: "system" },
  { id: "t-003", name: "Debian 12", os: "Debian", size: "1.9 GB", arch: "x86_64", featured: true, hypervisors: ["KVM", "VMware", "XenServer"], account: "system" },
  { id: "t-004", name: "Rocky Linux 9", os: "Rocky", size: "2.1 GB", arch: "x86_64", featured: false, hypervisors: ["KVM"], account: "system" },
  { id: "t-005", name: "Alpine 3.20", os: "Alpine", size: "320 MB", arch: "x86_64", featured: false, hypervisors: ["KVM"], account: "system" },
  { id: "t-006", name: "Windows Server 2022", os: "Windows", size: "9.2 GB", arch: "x86_64", featured: true, hypervisors: ["VMware", "Hyper-V"], account: "system" },
  { id: "t-007", name: "PostgreSQL 16", os: "Ubuntu", size: "2.6 GB", arch: "x86_64", featured: true, hypervisors: ["KVM"], account: "platform" },
  { id: "t-008", name: "Redis 7", os: "Alpine", size: "380 MB", arch: "x86_64", featured: false, hypervisors: ["KVM"], account: "platform" },
  { id: "t-009", name: "Ubuntu 24.04 + CUDA", os: "Ubuntu", size: "8.4 GB", arch: "x86_64", featured: false, hypervisors: ["KVM"], account: "research" },
  { id: "t-010", name: "k3s node", os: "Ubuntu", size: "2.5 GB", arch: "x86_64", featured: true, hypervisors: ["KVM"], account: "platform" },
];

export const mockKubernetesClusters: KubernetesCluster[] = [
  { id: "k-101", name: "prod-services", version: "1.30.4", zone: "syd-1", account: "platform", nodes: 7, state: "running", endpoint: "https://k8s-prod.example.internal" },
  { id: "k-102", name: "ci-builds", version: "1.29.8", zone: "mel-1", account: "engineering", nodes: 4, state: "running", endpoint: "https://k8s-ci.example.internal" },
  { id: "k-103", name: "ml-labs", version: "1.30.4", zone: "syd-2", account: "research", nodes: 3, state: "degraded", endpoint: "https://k8s-ml.example.internal" },
  { id: "k-104", name: "staging", version: "1.31.1", zone: "syd-1", account: "engineering", nodes: 2, state: "updating", endpoint: "https://k8s-staging.example.internal" },
];

export const mockDashboardMetrics: DashboardMetric[] = [
  { label: "Instances running", value: "9", denom: "/ 13", delta: "+3 (24h)", series: [4, 5, 5, 6, 7, 6, 7, 8, 9], color: "var(--accent)" },
  { label: "vCPUs allocated", value: "328", denom: "/ 512", delta: "64% of quota", series: [200, 220, 250, 290, 310, 320, 328], color: "var(--success)" },
  { label: "Memory allocated", value: "1.2", denom: " TiB", suffix: "/ 2 TiB", delta: "+128 GiB", series: [0.7, 0.8, 0.9, 1.0, 1.05, 1.15, 1.2], color: "var(--warning)" },
  { label: "Storage used", value: "18.4", denom: " TiB", suffix: "", delta: "+1.2 TiB", series: [10, 12, 14, 15, 16, 17, 18.4], color: "#ec4899" },
];

export const mockDashboardSummary = {
  onlineZones: mockZones.filter((zone) => zone.state === "enabled").length,
  totalZones: mockZones.length,
  totalHosts: mockZones.reduce((sum, zone) => sum + zone.hosts, 0),
  runningInstances: mockZones.reduce((sum, zone) => sum + zone.instances, 0),
};

export const mockCommandActions = [
  "Deploy instance",
  "Create network",
  "Upload template",
  "Restart console proxy",
] as const;
