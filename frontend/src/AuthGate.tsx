import { useEffect, useSyncExternalStore } from "react";
import { ArrowRight, Boxes, LockKeyhole, Route, Truck } from "lucide-react";
import App from "./App";
import { browserAuth, type AuthController } from "./auth";

/** The operations tree exists only for an API-validated session. Unmounting it
 * discards dialogs, drafts, and fetched data before another account can enter.
 */
export default function AuthGate({
  auth = browserAuth(),
}: {
  auth?: AuthController;
}) {
  const state = useSyncExternalStore(auth.subscribe, auth.getSnapshot);
  useEffect(() => {
    void auth.initialize();
  }, [auth]);
  if (state.status === "ready") {
    return (
      <App
        key={`${state.session.subject}:${state.session.tenantId}`}
        session={state.session}
        onSignOut={auth.signOut}
      />
    );
  }
  const busy = state.status === "loading";
  return (
    <main className="auth-shell">
      <section className="auth-story" aria-label="Welcome to FlowOps">
        <div className="brand">
          <span className="brand-icon">
            <Route size={23} />
          </span>
          <span>
            flowops<span className="brand-period">.</span>
          </span>
        </div>
        <div className="auth-story-content">
          <div className="eyebrow">OPERATIONS, IN SYNC</div>
          <h1>
            Every order.
            <br />
            Every move.
            <br />
            <span>One clear view.</span>
          </h1>
          <p>
            Keep your catalog, stock, and deliveries together in a workspace
            built for your team.
          </p>
          <div className="auth-features">
            <span>
              <Boxes size={18} />
              Know your stock
            </span>
            <span>
              <Truck size={18} />
              Keep deliveries moving
            </span>
          </div>
        </div>
        <small>From the warehouse to the doorstep.</small>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <span className="auth-lock">
            <LockKeyhole size={25} />
          </span>
          <div className="eyebrow">YOUR FLOWOPS WORKSPACE</div>
          <h2>Welcome back.</h2>
          <p>Sign in with your team account to continue.</p>
          {state.status !== "loading" &&
            "message" in state &&
            state.message && (
              <div className="notice error" role="alert">
                {state.message}
              </div>
            )}
          {busy ? (
            <p className="auth-progress" role="status">
              Opening your workspace…
            </p>
          ) : (
            <>
              <button
                className="primary auth-button"
                onClick={() => void auth.signIn()}
              >
                Sign in to FlowOps <ArrowRight size={17} />
              </button>
              {state.status === "error" && (
                <button
                  className="auth-retry"
                  onClick={() => void auth.retry()}
                >
                  Try again
                </button>
              )}
            </>
          )}
          <p className="auth-note">
            Your team’s data is available only in its own workspace.
          </p>
        </div>
      </section>
    </main>
  );
}
