import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Badge, DataTable, EmptyState, MetricTile, PageHeader, Toolbar } from '../design-system';
import { duplicateKindTone, time } from '../status.js';
import { api } from '../api.js';
import CaseDetailScreen from './CaseDetailScreen.jsx';

const POLL_MS = 5000;

const KIND_LABEL = {
  CORE_DUPLICATE: 'core duplicate',
  MISSING_AT_CORE: 'missing at core',
};

/**
 * UC-06 — Duplicate Report.
 *
 * The correct content of this screen is nothing (AC2/AC3) — it is a live cross-check,
 * recomputed on every visit, reading both sides of the module/core boundary rather than
 * trusting either alone (AC4): a `CORE_DUPLICATE` row is a reference the core itself shows more
 * than one account for; a `MISSING_AT_CORE` row is a case this module believes is OPENED whose
 * accountId the core's own list for that reference doesn't actually contain — a duplicate the
 * core-only view alone could never catch. Empty is not "no data"; empty is the passing result,
 * and the screen says so. A core that cannot be reached is never rendered as a silently empty
 * report (AC5) — that is an alarm of its own, distinct from "zero duplicates found".
 */
export default function DuplicateReportScreen() {
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null);

  const reload = useCallback(async () => {
    try {
      setReport(await api.getDuplicateReport());
      setError(null);
    } catch (e) {
      setError(e.message);
      setReport(null);
    }
  }, []);

  useEffect(() => {
    reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload]);

  const duplicates = report?.duplicates ?? [];

  const columns = [
    { key: 'applicationId', header: 'Application', mono: true },
    { key: 'reference', header: 'Case reference', mono: true, render: (r) => r.reference ?? '— no module record' },
    {
      key: 'kind',
      header: 'Kind',
      tight: true,
      render: (r) => <Badge tone={duplicateKindTone(r.kind)}>{KIND_LABEL[r.kind] ?? r.kind}</Badge>,
    },
    {
      key: 'coreAccountIds',
      header: 'Core account ids',
      render: (r) => (r.coreAccountIds.length > 0 ? r.coreAccountIds.join(', ') : '—'),
    },
  ];

  return (
    <>
      <PageHeader
        title="Duplicate Report"
        lede="this report should always be empty — a row here is a control failure"
      />

      {error && (
        <Alert tone="negative" title="Cannot verify">
          {error} — an unverifiable control is not a passing control.
        </Alert>
      )}

      {!error && report && (
        <>
          <Toolbar>
            <MetricTile label="Checked at" value={time(report.checkedAt)} />
            <MetricTile label="Core accounts scanned" value={report.coreAccountsScanned} />
            <MetricTile
              label="Duplicates"
              value={duplicates.length}
              tone={duplicates.length > 0 ? 'negative' : 'positive'}
            />
          </Toolbar>

          {duplicates.length > 0 && (
            <Alert tone="negative" title="Control failure">
              {duplicates.length} application{duplicates.length === 1 ? '' : 's'} has more than one core
              account, or a record this module believes is opened that the core cannot confirm. This
              should never happen — investigate before anything else.
            </Alert>
          )}

          <DataTable
            columns={columns}
            rows={duplicates}
            rowKey={(r) => r.applicationId}
            rowTone={() => 'negative'}
            onRowClick={(r) => setExpanded((prev) => (prev === r.applicationId ? null : r.applicationId))}
            expandedKey={expanded}
            renderExpanded={(r) => (
              <>
                {r.kind === 'MISSING_AT_CORE' ? (
                  <>
                    This module's own record for <strong>{r.applicationId}</strong> believes it holds an
                    account that the core's own list for this reference does not contain.
                  </>
                ) : (
                  <>
                    <strong>{r.coreAccountIds.length}</strong> core accounts for{' '}
                    <strong>{r.applicationId}</strong>: {r.coreAccountIds.join(', ')}
                    {!r.reference && ' — no matching row in this module’s own table either.'}
                  </>
                )}
                {r.reference && (
                  <div style={{ marginTop: '1rem' }}>
                    <CaseDetailScreen applicationId={r.applicationId} />
                  </div>
                )}
              </>
            )}
            empty={
              <EmptyState title="Empty — the control is working">
                No reference has more than one core account, and every OPENED case matches what the
                core shows. This is the report's correct, everyday state.
              </EmptyState>
            }
          />
        </>
      )}
    </>
  );
}
