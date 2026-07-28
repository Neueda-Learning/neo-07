// Thin fetch wrapper. Base is empty so paths are same-origin (nginx proxies in the
// container, Vite proxies in dev). Override with VITE_API_BASE if you must.
//
// Everything the UI calls goes through here on purpose: in the deployed stack the whole
// app is served under a path prefix (/neo-07) and VITE_API_BASE is how every URL
// picks it up. A raw fetch('/api/...') inside a component works on your laptop and 404s
// on the load balancer.
const BASE = import.meta.env.VITE_API_BASE || '';

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
    } catch {
      /* non-JSON error body */
    }
    const error = new Error(message);
    error.status = res.status;
    throw error;
  }
  if (res.status === 204) return null;
  return res.json();
}

// Applications arrive from the orchestrator — the real one, or the sidecar playing it at
// http://localhost:9000 — never from a button in here. That is the contract: your module is
// called, it does not call itself. The two exceptions are operator actions on THIS module's own
// state: retrying a parked case (UC-04) and operating the mock core's dials/config (UC-05, UC-08)
// — neither one re-submits or mutates an application.
export const api = {
  health: () => request('/health'),
  info: () => request('/info'),
  listApplications: () => request('/api/v1/applications'),
  getApplication: (id) => request(`/api/v1/applications/${id}`),
  // UC-01 — Search Accounts. Empty `q` is never sent: the board starts empty by design, so the
  // caller only calls this once there is something to search for.
  searchAccounts: (q) => request(`/api/v1/accounts/search?q=${encodeURIComponent(q)}`),
  // Hydrates one row's applicant name, live — never cached server-side, so the UI caches it.
  getApplicant: (applicationId) => request(`/api/v1/accounts/${applicationId}/applicant`),
  // UC-02 — Review Case + Attempt Log: one case's anchor record plus its ordered core calls.
  getCase: (applicationId) => request(`/cases/${applicationId}`),
  // UC-03 — View Applicant: the sidebar proxy, never persisted.
  getCaseApplicant: (applicationId) => request(`/cases/${applicationId}/applicant`),
  // UC-04 — Failed-Opens Queue. `retryCase` is this module's one write action from the UI: an
  // operator re-running probe-then-open once the core is believed to be back, never the applicant.
  getFailedOpensQueue: () => request('/queue'),
  retryCase: (applicationId) => request(`/cases/${applicationId}/retry`, { method: 'POST' }),
  // UC-06 — Duplicate Report. Read-only: a live cross-check, recomputed on every visit.
  getDuplicateReport: () => request('/reports/duplicates'),
  // UC-05 — Operate Mock Core Control Panel. The mock's own live dials, in-memory on that
  // service — PUT is a partial update, only the fields present in the body change.
  getDials: () => request('/core/admin/dials'),
  updateDials: (patch) => request('/core/admin/dials', { method: 'PUT', body: JSON.stringify(patch) }),
  // UC-08 — Edit Core Config. Insert-only: POST always adds a new version, never edits one.
  createConfig: (payload) => request('/config', { method: 'POST', body: JSON.stringify(payload) }),
  getConfigVersions: () => request('/config/versions'),
};
