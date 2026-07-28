import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  Field,
  FormActions,
  FormGrid,
  PageHeader,
  Stack,
  TextInput,
} from '../design-system';
import { time } from '../status.js';
import { api } from '../api.js';

const PRODUCT_CODES = ['CREDIT_CARD_STANDARD', 'CREDIT_CARD_REWARDS', 'CREDIT_CARD_STUDENT'];

const emptyCatalogueRow = () => ({ apr: '', limitMin: '', limitMax: '' });

function emptyForm() {
  return {
    retryBudget: '',
    timeoutMs: '',
    coreBaseUrl: '',
    catalogue: Object.fromEntries(PRODUCT_CODES.map((code) => [code, emptyCatalogueRow()])),
  };
}

function toFormValues(version) {
  return {
    retryBudget: String(version.retryBudget),
    timeoutMs: String(version.timeoutMs),
    coreBaseUrl: version.coreBaseUrl,
    catalogue: Object.fromEntries(
      PRODUCT_CODES.map((code) => {
        const entry = version.catalogue?.[code] ?? {};
        return [
          code,
          {
            apr: entry.apr != null ? String(entry.apr) : '',
            limitMin: entry.limitMin != null ? String(entry.limitMin) : '',
            limitMax: entry.limitMax != null ? String(entry.limitMax) : '',
          },
        ];
      })
    ),
  };
}

function toPayload(form) {
  return {
    retryBudget: Number(form.retryBudget),
    timeoutMs: Number(form.timeoutMs),
    coreBaseUrl: form.coreBaseUrl,
    catalogue: Object.fromEntries(
      PRODUCT_CODES.map((code) => [
        code,
        {
          apr: Number(form.catalogue[code].apr),
          limitMin: Number(form.catalogue[code].limitMin),
          limitMax: Number(form.catalogue[code].limitMax),
        },
      ])
    ),
  };
}

/**
 * UC-08 — Edit Core Config.
 *
 * Insert-only: every submit adds a new version, never edits one — an old case's
 * coreConfigVersion stays pinned to the row that decided it (AC4). No timeoutTrap field here —
 * that dial lives only on the mock's live state (UC-05's Core Control Panel), never on this
 * versioned policy.
 */
export default function CoreConfigScreen() {
  const [versions, setVersions] = useState([]);
  const [error, setError] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [form, setForm] = useState(emptyForm);
  const [submitting, setSubmitting] = useState(false);

  const reload = useCallback(async () => {
    try {
      const rows = await api.getConfigVersions();
      setVersions(rows);
      setLoadError(null);
      const current = rows.find((v) => v.current);
      if (current) setForm(toFormValues(current));
    } catch (e) {
      setLoadError(e.message);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const updateField = (field, value) => setForm((prev) => ({ ...prev, [field]: value }));
  const updateCatalogue = (code, field, value) =>
    setForm((prev) => ({
      ...prev,
      catalogue: { ...prev.catalogue, [code]: { ...prev.catalogue[code], [field]: value } },
    }));

  const submit = useCallback(
    async (e) => {
      e.preventDefault();
      setSubmitting(true);
      setError(null);
      try {
        await api.createConfig(toPayload(form));
        await reload();
      } catch (err) {
        setError(err.message);
      } finally {
        setSubmitting(false);
      }
    },
    [form, reload]
  );

  const columns = [
    { key: 'version', header: 'Version', tight: true, render: (r) => `v${r.version}` },
    { key: 'retryBudget', header: 'Retry budget', numeric: true },
    { key: 'timeoutMs', header: 'Timeout', numeric: true, render: (r) => `${r.timeoutMs} ms` },
    { key: 'coreBaseUrl', header: 'Core base URL', mono: true },
    { key: 'effectiveFrom', header: 'Effective from', render: (r) => time(r.effectiveFrom) },
    {
      key: 'current',
      header: '',
      tight: true,
      render: (r) => (r.current ? <Badge tone="positive">current</Badge> : null),
    },
  ];

  return (
    <>
      <PageHeader
        title="Core Configuration"
        lede="retry budget, timeout and product catalogue — ships as data, no deploy"
      />

      <Stack gap={6}>
        <Card title="New version" subtitle="inserts a new version — never edits an existing one">
          <form onSubmit={submit}>
            {error && (
              <Alert tone="negative" title="Rejected">
                {error}
              </Alert>
            )}

            <Stack gap={5}>
              <FormGrid cols={3}>
                <Field label="Retry budget" hint="1–10">
                  {({ id }) => (
                    <TextInput
                      id={id}
                      type="number"
                      min={1}
                      max={10}
                      required
                      value={form.retryBudget}
                      onChange={(e) => updateField('retryBudget', e.target.value)}
                    />
                  )}
                </Field>
                <Field label="Timeout" hint="200–30000 ms">
                  {({ id }) => (
                    <TextInput
                      id={id}
                      type="number"
                      min={200}
                      max={30000}
                      required
                      value={form.timeoutMs}
                      onChange={(e) => updateField('timeoutMs', e.target.value)}
                    />
                  )}
                </Field>
                <Field label="Core base URL">
                  {({ id }) => (
                    <TextInput
                      id={id}
                      required
                      value={form.coreBaseUrl}
                      onChange={(e) => updateField('coreBaseUrl', e.target.value)}
                    />
                  )}
                </Field>
              </FormGrid>

              {PRODUCT_CODES.map((code) => (
                <FormGrid.Full key={code}>
                  <FormGrid cols={4}>
                    <Field label={code} hint="product code">
                      <span className="ds-input ds-input--mono" aria-hidden="true">
                        {code}
                      </span>
                    </Field>
                    <Field label="APR">
                      {({ id }) => (
                        <TextInput
                          id={id}
                          type="number"
                          step="0.1"
                          required
                          value={form.catalogue[code].apr}
                          onChange={(e) => updateCatalogue(code, 'apr', e.target.value)}
                        />
                      )}
                    </Field>
                    <Field label="Limit min">
                      {({ id }) => (
                        <TextInput
                          id={id}
                          type="number"
                          required
                          value={form.catalogue[code].limitMin}
                          onChange={(e) => updateCatalogue(code, 'limitMin', e.target.value)}
                        />
                      )}
                    </Field>
                    <Field label="Limit max">
                      {({ id }) => (
                        <TextInput
                          id={id}
                          type="number"
                          required
                          value={form.catalogue[code].limitMax}
                          onChange={(e) => updateCatalogue(code, 'limitMax', e.target.value)}
                        />
                      )}
                    </Field>
                  </FormGrid>
                </FormGrid.Full>
              ))}

              <FormActions>
                <Button type="submit" variant="primary" busy={submitting} busyLabel="Saving…">
                  Create new version
                </Button>
              </FormActions>
            </Stack>
          </form>
        </Card>

        {loadError && (
          <Alert tone="negative" title="Could not load version history">
            {loadError}
          </Alert>
        )}

        <DataTable
          columns={columns}
          rows={versions}
          rowKey={(r) => r.version}
          maxRows={null}
          footnote="oldest first · current flagged"
          empty={<EmptyState title="No versions yet">Seed data should have created v1 on first boot.</EmptyState>}
        />
      </Stack>
    </>
  );
}
