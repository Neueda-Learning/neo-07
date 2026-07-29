import React, { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Caption,
  KeyValue,
  PageHeader,
  Split,
  Timeline,
} from '../design-system';
import { money, outcomeTone } from '../status.js';
import { api } from '../api.js';
import OverrideCaseModal from './OverrideCaseModal.jsx';

function attemptTitle(attempt) {
  return `${attempt.kind} ${attempt.result}`;
}

/**
 * UC-02 — Review Case + Attempt Log, UC-03 — View Applicant.
 *
 * Two independent fetches, not one combined call: the anchor + attempt log (this module's own
 * data) and the applicant sidebar (a live orchestrator proxy) must fail separately (UC-03 AC#4) —
 * an unreachable orchestrator degrades only the sidebar, never the case detail beside it.
 */
export default function CaseDetailScreen({ applicationId }) {
  const [caseDetail, setCaseDetail] = useState(null);
  const [caseError, setCaseError] = useState(null);
  const [applicant, setApplicant] = useState(null);
  const [applicantError, setApplicantError] = useState(null);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideNotice, setOverrideNotice] = useState(null);

  const loadCase = () => {
    setCaseError(null);
    api
      .getCase(applicationId)
      .then(setCaseDetail)
      .catch((e) => setCaseError(e.message));
  };

  const loadApplicant = () => {
    setApplicantError(null);
    setApplicant(null);
    api
      .getCaseApplicant(applicationId)
      .then(setApplicant)
      .catch((e) => setApplicantError(e.message));
  };

  useEffect(() => {
    setCaseDetail(null);
    loadCase();
    loadApplicant();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationId]);

  const sidebar = applicantError ? (
    <Alert
      tone="negative"
      title="Applicant lookup unavailable"
      action={
        <Button variant="ghost" size="sm" onClick={loadApplicant}>
          Retry
        </Button>
      }
    >
      The case detail beside this is unaffected.
    </Alert>
  ) : applicant ? (
    <>
      <KeyValue
        items={[
          { label: 'Full name', value: applicant.fullName ?? '—' },
          { label: 'Date of birth', value: applicant.dateOfBirth ?? '—', mono: true },
          { label: 'Product requested', value: applicant.productCode ?? '—', mono: true },
          { label: 'Requested limit', value: money(applicant.requestedCreditLimit) },
          { label: 'Channel', value: applicant.channel ?? '—' },
        ]}
      />
      <Caption>fetched live, never stored</Caption>
    </>
  ) : (
    <span className="ds-empty__title">…</span>
  );

  if (caseError) {
    return (
      <>
        <PageHeader title={`Case ${applicationId}`} />
        <Alert
          tone="negative"
          title="Case not found"
          action={
            <Button variant="ghost" size="sm" onClick={loadCase}>
              Retry
            </Button>
          }
        >
          {caseError}
        </Alert>
      </>
    );
  }

  if (!caseDetail) {
    return (
      <>
        <PageHeader title={`Case ${applicationId}`} />
        <span className="ds-empty__title">Loading…</span>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={`Case ${applicationId}`}
        badge={<Badge tone={outcomeTone(caseDetail.outcome)}>{caseDetail.outcome}</Badge>}
        meta={`${caseDetail.productCode ?? '—'} · config v${caseDetail.coreConfigVersion ?? '—'}`}
        actions={
          <Button variant="primary" onClick={() => setOverrideOpen(true)}>
            Override decision…
          </Button>
        }
      />

      {overrideNotice && (
        <Alert tone="positive" title="Override recorded">
          {overrideNotice}
        </Alert>
      )}

      <Split sidebar={sidebar}>
        <Card title="Account" subtitle={caseDetail.reference}>
          <KeyValue
            items={[
              { label: 'Account id', value: caseDetail.accountId ?? '—', mono: true },
              { label: 'Credit amount', value: money(caseDetail.creditAmount) },
              { label: 'Agreement id', value: caseDetail.agreementId ?? '—', mono: true },
              { label: 'Customer id', value: caseDetail.customerId ?? '—', mono: true },
              { label: 'Card id', value: caseDetail.cardId ?? '—', mono: true },
            ]}
          />
        </Card>
        <Card
          title="Attempt log"
          subtitle={`${caseDetail.attempts.length} call${caseDetail.attempts.length === 1 ? '' : 's'}`}
        >
          <Timeline
            items={caseDetail.attempts.map((attempt, index) => ({
              id: index,
              title: attemptTitle(attempt),
              detail: `${attempt.latencyMs}ms`,
              when: `cycle ${attempt.cycle}`,
            }))}
          />
        </Card>
        <Card
          title="Override history"
          subtitle={`${caseDetail.overrides?.length ?? 0} decision${
            caseDetail.overrides?.length === 1 ? '' : 's'
          }`}
        >
          {caseDetail.overrides?.length ? (
            <Timeline
              items={caseDetail.overrides.map((override, index) => ({
                id: index,
                title: `${override.oldOutcome} → ${override.newOutcome}`,
                detail: `${override.reason} · ${override.operator}${
                  override.accountId ? ` · ${override.accountId}` : ''
                }`,
                when: new Date(override.overriddenAt).toLocaleString(),
              }))}
            />
          ) : (
            <Caption>No manual decisions recorded.</Caption>
          )}
        </Card>
      </Split>

      <OverrideCaseModal
        open={overrideOpen}
        applicationId={applicationId}
        currentOutcome={caseDetail.outcome}
        currentAccountId={caseDetail.accountId}
        onClose={() => setOverrideOpen(false)}
        onSaved={(updated) => {
          setCaseDetail(updated);
          setOverrideOpen(false);
          setOverrideNotice('The case was updated, audited, and the orchestrator was notified.');
        }}
      />
    </>
  );
}
