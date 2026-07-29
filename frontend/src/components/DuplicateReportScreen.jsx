import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Button, DataTable, EmptyState, MetricTile, PageHeader, Toolbar } from '../design-system';
import { time } from '../status.js';
import { api } from '../api.js';

const POLL_MS = 5000;

/**
 * UC-06 — Duplicate Report.
 *
 * The correct content of this screen is nothing (AC2/AC3) — it is a live cross-check,
 * recomputed on every visit, reading the core's own account store rather than trusting this
 * module's table alone (AC4). Empty is not "no data"; empty is the passing result, and the
 * screen says so. A core that cannot be reached is never rendered as a silently empty report
 * (AC5) — that is an alarm of its own, distinct from "zero duplicates found".
 *
 * `onOpenCase` is how a row's case attempt log is one click away (AC6) — only offered when the
 * duplicate has a matching module record; an orphan (core-only) duplicate has no case to open.
 */
export default function DuplicateReportScreen({ onOpenCase }) {
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
      key: 'coreAccountIds',
      header: 'Core account ids',
      render: (r) => r.coreAccountIds.join(', '),
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
              account. This should never happen — investigate before anything else.
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
                <strong>{r.coreAccountIds.length}</strong> core accounts for{' '}
                <strong>{r.applicationId}</strong>: {r.coreAccountIds.join(', ')}
                {r.reference ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    style={{ marginLeft: 'var(--ds-space-4)' }}
                    onClick={(e) => {
                      e.stopPropagation();
                      onOpenCase?.(r.applicationId);
                    }}
                  >
                    View case attempt log →
                  </Button>
                ) : (
                  ' — no matching row in this module\u2019s own table either.'
                )}
              </>
            )}
            empty={
              <EmptyState title="Empty — the control is working">
                No reference has more than one core account. This is the report's correct,
                everyday state.
              </EmptyState>
            }
          />
        </>
      )}
    </>
  );
}
