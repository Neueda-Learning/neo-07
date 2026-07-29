import React, { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Field,
  FormActions,
  FormGrid,
  Modal,
  Select,
  Textarea,
  TextInput,
} from '../design-system';
import { api } from '../api.js';

const OUTCOMES = [
  { value: 'OPENED', label: 'OPENED — account confirmed' },
  { value: 'FAILED', label: 'FAILED — account must remain parked' },
];

/** UC-07's one operator mutation: correct the outcome without making any Core call. */
export default function OverrideCaseModal({
  open,
  applicationId,
  currentOutcome,
  currentAccountId,
  onClose,
  onSaved,
}) {
  const [newOutcome, setNewOutcome] = useState('');
  const [accountId, setAccountId] = useState('');
  const [reason, setReason] = useState('');
  const [operator, setOperator] = useState('');
  const [errors, setErrors] = useState({});
  const [requestError, setRequestError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    setNewOutcome('');
    setAccountId(currentAccountId ?? '');
    setReason('');
    setOperator('');
    setErrors({});
    setRequestError(null);
    setSaving(false);
  }, [open, currentAccountId]);

  const validate = () => {
    const next = {};
    if (!newOutcome) next.newOutcome = 'Choose OPENED or FAILED.';
    if (!reason.trim()) next.reason = 'Reason is required.';
    if (!operator.trim()) next.operator = 'Operator is required.';
    if (newOutcome === 'OPENED' && !accountId.trim()) {
      next.accountId = 'Account id is required when opening a case.';
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const submit = async (event) => {
    event.preventDefault();
    if (!validate()) return;

    setSaving(true);
    setRequestError(null);
    try {
      const updated = await api.overrideCase(applicationId, {
        newOutcome,
        reason: reason.trim(),
        operator: operator.trim(),
        accountId: newOutcome === 'OPENED' ? accountId.trim() : null,
      });
      onSaved(updated);
    } catch (error) {
      setRequestError(error.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Override decision"
      onClose={saving ? undefined : onClose}
      footer={
        <FormActions>
          <Button
            variant="primary"
            type="submit"
            form="override-case-form"
            busy={saving}
            busyLabel="Saving override…"
          >
            Confirm override
          </Button>
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            Cancel
          </Button>
        </FormActions>
      }
    >
      <form id="override-case-form" onSubmit={submit}>
        <p style={{ marginTop: 0 }}>
          Correct <strong>{applicationId}</strong>, currently <strong>{currentOutcome}</strong>.
          This records a human decision and does not call Core Banking.
        </p>

        {requestError && (
          <Alert tone="negative" title="Override failed">
            {requestError}
          </Alert>
        )}

        <FormGrid>
          <Field label="New outcome" required error={errors.newOutcome}>
            {({ id, invalid, describedBy }) => (
              <Select
                id={id}
                value={newOutcome}
                placeholder="Choose an outcome"
                options={OUTCOMES}
                invalid={invalid}
                aria-describedby={describedBy}
                onChange={(event) => {
                  setNewOutcome(event.target.value);
                  setErrors((current) => ({ ...current, newOutcome: null, accountId: null }));
                }}
              />
            )}
          </Field>

          <Field
            label="Confirmed account id"
            required={newOutcome === 'OPENED'}
            hint={newOutcome === 'OPENED' ? 'Required: supplied by the Core team.' : 'Used only for OPENED.'}
            error={errors.accountId}
          >
            {({ id, invalid, describedBy }) => (
              <TextInput
                id={id}
                mono
                value={accountId}
                disabled={newOutcome !== 'OPENED'}
                invalid={invalid}
                aria-describedby={describedBy}
                onChange={(event) => {
                  setAccountId(event.target.value);
                  setErrors((current) => ({ ...current, accountId: null }));
                }}
              />
            )}
          </Field>

          <FormGrid.Full>
            <Field label="Reason" required error={errors.reason}>
              {({ id, invalid, describedBy }) => (
                <Textarea
                  id={id}
                  rows={4}
                  maxLength={1000}
                  value={reason}
                  invalid={invalid}
                  aria-describedby={describedBy}
                  placeholder="For example: account confirmed by Core team ticket CORE-4411"
                  onChange={(event) => {
                    setReason(event.target.value);
                    setErrors((current) => ({ ...current, reason: null }));
                  }}
                />
              )}
            </Field>
          </FormGrid.Full>

          <FormGrid.Full>
            <Field label="Operator" required error={errors.operator}>
              {({ id, invalid, describedBy }) => (
                <TextInput
                  id={id}
                  value={operator}
                  maxLength={255}
                  invalid={invalid}
                  aria-describedby={describedBy}
                  placeholder="b.dimovski"
                  onChange={(event) => {
                    setOperator(event.target.value);
                    setErrors((current) => ({ ...current, operator: null }));
                  }}
                />
              )}
            </Field>
          </FormGrid.Full>
        </FormGrid>
      </form>
    </Modal>
  );
}
