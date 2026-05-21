import { headers } from "next/headers";

import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { getAccountsFromBff } from "@/lib/cloudstack/accounts";
import type { Account } from "@/lib/mock-data";

export const metadata = { title: "Accounts" };
export const dynamic = "force-dynamic";

export default async function Page() {
  const accounts = await getAccountsFromBff({ requestHeaders: headers() });
  const active = accounts.filter((account) => account.state === "active").length;
  const disabled = accounts.length - active;
  const admins = accounts.filter((account) => account.role === "Admin" || account.role === "Domain admin").length;
  const users = accounts.reduce((sum, account) => sum + account.users, 0);
  const instances = accounts.reduce((sum, account) => sum + account.instances, 0);

  return (
    <>
      <PageHeader
        title="Accounts"
        description={`${accounts.length} accounts, ${users} users, and ${instances} instances across your scope`}
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Badge variant="info" size="md">{accounts.length} total</Badge>
        <Badge variant="success" size="md">{active} active</Badge>
        <Badge variant={disabled > 0 ? "warning" : "default"} size="md">{disabled} disabled</Badge>
        <Badge variant="accent" size="md">{admins} admins</Badge>
      </div>

      <Card className="p-0">
        <Table className="min-w-[900px]">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Account</TableHead>
              <TableHead>Domain</TableHead>
              <TableHead>Role</TableHead>
              <TableHead className="text-right">Users</TableHead>
              <TableHead className="text-right">Instances</TableHead>
              <TableHead className="pr-4">State</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {accounts.length > 0 ? (
              accounts.map((account) => (
                <TableRow key={`${account.domain}-${account.name}`}>
                  <TableCell className="pl-4 font-medium">{account.name}</TableCell>
                  <TableCell>{account.domain}</TableCell>
                  <TableCell>
                    <Badge variant={roleVariant(account.role)}>{account.role}</Badge>
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{account.users}</TableCell>
                  <TableCell className="text-right tabular-nums">{account.instances}</TableCell>
                  <TableCell className="pr-4">
                    <Badge variant={account.state === "active" ? "success" : "warning"}>
                      {account.state === "active" ? "Active" : "Disabled"}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-sm text-[color:var(--fg-muted)]">
                  No accounts found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Card>
    </>
  );
}

function roleVariant(role: Account["role"]): "accent" | "info" | "default" {
  switch (role) {
    case "Admin":
    case "Domain admin":
      return "accent";
    case "Service":
      return "info";
    default:
      return "default";
  }
}
