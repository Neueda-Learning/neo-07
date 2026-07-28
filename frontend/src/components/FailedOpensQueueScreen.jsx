import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Badge, Button, DataTable, EmptyState, PageHeader } from '../design-system';
import { time } from '../status.js';
import { api } from '../api.js';

const POLL_MS = 3000;

/**
 * UC-04 — Failed-Opens Queue.
 *
 * Every case here is parked on ACC_CORE_UNAVAILABLE, not a business "no" — the applicant did
 * nothing wrong, the core was just down when this journey tried to open. An operator's only
 * action is Retry: it re-enters probe-then-open at the top (the outage may have hidden an
 * account the core already created), and a successful retry drops the row on the next load
 * (AC7). Nothing here polls in the background *for* the operator — retrying is always their
 * own click, never automatic (out of scope, by design).
 */
export default function FailedOpensQueueScreen() {
  const [rows, setRows] = useState([]);
  const [error, setError] = useState(null);
  const [retrying, setRetrying] = useState({});
  const [applicants, setApplicants] = useState({});

  const reload = useCallback(async () => {
    try {
      setRows(await api.getFailedOpensQueue());
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload]);

  // Live hydration, same shape as the Account Board — never for a row already resolved.
  const fetchApplicant = useCallback((applicationId) => {
    setApplicants((prev) => ({ ...prev, [applicationId]: { status: 'loading' } }));
    api
      .getApplicant(applicationId)
      .then((body) => {
        setApplicants((prev) => ({
          ...prev,
          [applicationId]: { status: 'ok', fullName: body?.applicant?.fullName ?? '—' },
        }));
      })
      .catch(() => {
        setApplicants((prev) => ({ ...prev, [applicationId]: { status: 'error' } }));
      });
  }, []);

  useEffect(() => {
    rows.forEach((row) => {
      if (!applicants[row.applicationId]) {
        fetchApplicant(row.applicationId);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows]);

  const retry = useCallback(
    async (applicationId) => {
      setRetrying((prev) => ({ ...prev, [applicationId]: true }));
      setError(null);
      try {
        await api.retryCase(applicationId);
        await reload();
      } catch (e) {
        setError(e.message);
      } finally {
        setRetrying((prev) => ({ ...prev, [applicationId]: false }));
      }
    },
    [reload]
  );

  const columns = [
    { key: 'applicationId', header: 'Application', mono: true },
    {
      key: 'applicant',
      header: 'Applicant',
      render: (r) => {
        const entry = applicants[r.applicationId];
        if (!entry || entry.status === 'loading') return <span className="ds-empty__title">…</span>;
        if (entry.status === 'error') {
          return (
            <Button variant="ghost" size="sm" onClick={() => fetchApplicant(r.applicationId)}>
              — retry
            </Button>
          );
        }
        return entry.fullName;
      },
    },
    { key: 'reference', header: 'Reference', mono: true },
    {
      key: 'coreConfigVersion',
      header: 'Core config',
      tight: true,
      render: (r) => <Badge tone="info">v{r.coreConfigVersion}</Badge>,
    },
    { key: 'attemptCount', header: 'Attempts', numeric: true },
    { key: 'createdAt', header: 'Failed since', render: (r) => time(r.createdAt) },
    {
      key: 'actions',
      header: '',
      tight: true,
      render: (r) => (
        <Button
          size="sm"
          disabled={Boolean(retrying[r.applicationId])}
          onClick={() => retry(r.applicationId)}
        >
          {retrying[r.applicationId] ? 'Retrying…' : 'Retry'}
        </Button>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Failed-Opens Queue"
        lede="oldest first · cases parked while the core was unreachable · max 10 rows"
      />

      {error && (
        <Alert tone="negative" title="Retry failed">
          {error}
        </Alert>
      )}

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(r) => r.applicationId}
        footnote="oldest first"
        empty={
          <EmptyState title="Core healthy — nothing parked">
            No case is waiting on the core right now. A row only appears here once a retry budget
            is exhausted with the core unreachable.
          </EmptyState>
        }
      />
    </>
  );
}
