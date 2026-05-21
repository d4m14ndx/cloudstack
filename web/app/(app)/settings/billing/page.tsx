import type { Metadata } from "next";
import { getTranslations } from "next-intl/server";

import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/ui/empty-state";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("Settings.pages.billing");

  return { title: t("metadataTitle") };
}

export default async function Page() {
  const t = await getTranslations("Settings.pages.billing");

  return (
    <>
      <PageHeader title={t("title")} description={t("description")} />
      <section className="rounded-[var(--radius-lg)] border border-dashed border-[color:var(--border)] px-6 py-10">
        <EmptyState
          title={t("emptyState.title")}
          description={t("emptyState.description")}
        />
      </section>
    </>
  );
}
