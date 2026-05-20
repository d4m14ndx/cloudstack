import type { CurrentUser } from "@/lib/auth/types";

declare module "next-auth" {
  interface Session {
    user: CurrentUser;
  }

  interface User {
    id: CurrentUser["id"];
    username: CurrentUser["username"];
    email: CurrentUser["email"];
    name: CurrentUser["name"];
    role: CurrentUser["role"];
    domain: CurrentUser["domain"];
    domainId: CurrentUser["domainId"];
  }
}
