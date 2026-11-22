"use client";

import { useActionState, useEffect, useTransition } from "react";
import { signIn, signOut, type SignInResult } from "../admin/actions";

export function SignInForm() {
  const [state, action, pending] = useActionState<SignInResult, FormData>(signIn, null);
  useEffect(() => {
    if (state?.ok) window.location.replace("/admin");
  }, [state]);
  return (
    <form action={action}>
      {state && !state.ok && (
        <div className="err" role="alert">
          {state.error}
        </div>
      )}
      <label className="sr" htmlFor="password">
        Password
      </label>
      <input id="password" name="password" type="password" autoComplete="current-password" required autoFocus placeholder="Password" />
      <button className="btn" type="submit" disabled={pending}>
        {pending ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}

export function SignOutButton() {
  const [pending, start] = useTransition();
  return (
    <button
      type="button"
      disabled={pending}
      onClick={() =>
        start(async () => {
          await signOut();
          window.location.replace("/admin/login?out=1");
        })
      }
    >
      Sign out
    </button>
  );
}
