import type { Metadata } from "next";
import { AdminBar } from "../../components/AdminBar";
import { adminPassword } from "@/lib/admin";
import { SignInForm } from "../../components/AdminForms";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Sign in" };

export default async function Login({ searchParams }: { searchParams: Promise<{ out?: string }> }) {
  const { out } = await searchParams;
  const enabled = adminPassword() !== null;
  return (
    <>
      <AdminBar signedIn={false} />
      <main className="login">
        <h1>Sign in</h1>
        {enabled ? (
          <>
            <p>{out ? "You’re signed out." : "The crawl health pages are for the people who run this site."}</p>
            <SignInForm />
          </>
        ) : (
          <p>The admin pages are switched off: no admin password is configured for this site.</p>
        )}
      </main>
    </>
  );
}
