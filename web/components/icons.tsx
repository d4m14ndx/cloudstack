/**
 * Centralized icon exports. Lucide React with 1.6 stroke matches the design
 * handoff's custom icon set near-1:1. Aliased to design-intent names so
 * screen code reads cleanly.
 *
 * If a Lucide icon is ever visually wrong, port the design's custom version
 * from `new-ui/design_handoff_cloudstack_ui/icons.jsx` into this file and
 * swap the export.
 */
export {
  // Navigation
  LayoutGrid    as IconOverview,
  Box           as IconInstances,
  Network       as IconNetworks,
  HardDrive     as IconVolumes,
  Image         as IconTemplates,
  Hexagon       as IconKubernetes,
  ScrollText    as IconEvents,
  Users         as IconAccounts,
  Key           as IconSshKeys,
  ShieldCheck   as IconSecurity,
  Server        as IconInfrastructure,
  FolderTree    as IconDomains,
  CreditCard    as IconBilling,
  Settings      as IconSettings,
  LogOut        as IconLogout,
  // Topbar
  Search,
  Bell,
  HelpCircle,
  Code,
  Plus,
  Sun,
  Moon,
  // Actions
  ChevronRight,
  ChevronDown,
  ChevronUp,
  ChevronLeft,
  MoreHorizontal,
  MoreVertical,
  Check,
  X,
  Copy,
  Download,
  Upload,
  RefreshCw,
  Trash2,
  Edit,
  ExternalLink,
  // Resource
  Globe,
  Cpu,
  MemoryStick,
  Database,
  Cloud,
  Layers,
  Tag,
  // States
  AlertCircle,
  AlertTriangle,
  Info,
  CheckCircle2,
  Loader2,
  Zap,
  // Misc
  Sliders,
  Command,
  Terminal,
} from "lucide-react";
