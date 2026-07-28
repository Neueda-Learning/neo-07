import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  DataTable,
  EmptyState,
  PageHeader,
  SearchInput,
  Toolbar,
} from '../design-system';
import { money, outcomeTone } from '../status.js';
import { api } from '../api.js';

const DEBOUNCE_MS = 350;

function formatOpenedAt(iso) {
  return iso ? new Date(iso).toLocaleString() : '—';
}

/**
 * UC-01 — Search Accounts: the Account Board.
 *
 * Empty by default (AC1) — nothing is fetched until an operator types an application id or an
 * applicant name into the one search box; `q` covers both, the backend decides which it is. The
 * applicant-name column is never part of the search response (the schema has no name column to
 * search) — each visible row hydrates its own name live, through the applicant proxy, and the
 * result is cached for the life of this screen (AC4).
 */
export default function AccountBoardScreen() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [hasMore, setHasMore] = useState(false);
  const [searched, setSearched] = useState(false);
  const [error, setError] = useState(null);
  const [applicants, setApplicants] = useState({});
  const debounceRef = useRef(null);

  const runSearch = useCallback(async (q) => {
    const needle = q.trim();
    if (!needle) {
      setResults([]);
      setHasMore(false);
      setSearched(false);
      setError(null);
      return;
    }
    try {
      const response = await api.searchAccounts(needle);
      setResults(response.results ?? []);
      setHasMore(Boolean(response.hasMore));
      setSearched(true);
      setError(null);
    } catch (e) {
      setError(e.message);
      setSearched(true);
    }
  }, []);

  const onQueryChange = (e) => {
    const value = e.target.value;
    setQuery(value);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => runSearch(value), DEBOUNCE_MS);
  };

  useEffect(() => () => clearTimeout(debounceRef.current), []);

  // Live hydration, one GET per visible row, at most 10 per render — never for a row already
  // resolved (AC4). A failed lookup is cached too, as a retryable placeholder (AC6).
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
    results.forEach((row) => {
      if (!applicants[row.applicationId]) {
        fetchApplicant(row.applicationId);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [results]);

  const columns = [
    { key: 'applicationId', header: 'Application', mono: true },
    {
      key: 'outcome',
      header: 'Outcome',
      tight: true,
      render: (r) => <Badge tone={outcomeTone(r.outcome)}>{r.outcome}</Badge>,
    },
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
    { key: 'creditAmount', header: 'Credit amount', numeric: true, render: (r) => money(r.creditAmount) },
    { key: 'openedAt', header: 'Opened', render: (r) => formatOpenedAt(r.openedAt) },
  ];

  return (
    <>
      <PageHeader
        title="Account Board"
        lede="find an account case by application id or applicant name · empty until you search, never more than 10 rows"
      />

      {error && (
        <Alert tone="negative" title="Search failed">
          {error} — try again, or narrow the search.
        </Alert>
      )}

      <Toolbar>
        <SearchInput
          grow
          placeholder="Application id or applicant name"
          value={query}
          onChange={onQueryChange}
          aria-label="Search accounts"
        />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={results}
        total={hasMore ? results.length + 1 : results.length}
        rowKey={(r) => r.applicationId}
        footnote="newest first"
        empty={
          <EmptyState title={searched ? 'No account matches that' : 'Search to see accounts here'}>
            {searched ? (
              <>Try a different application id, or the applicant's name.</>
            ) : (
              <>
                Type an <strong>application id</strong> or an <strong>applicant name</strong> above — this
                board never shows a row until you do.
              </>
            )}
          </EmptyState>
        }
      />
    </>
  );
}
