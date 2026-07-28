import React, { useCallback, useEffect, useState } from 'react';
import { Alert, Card, Checkbox, PageHeader, Slider, Stack, StatusPill } from '../design-system';
import { api } from '../api.js';

const POLL_MS = 5000;

/**
 * UC-05 — Operate Mock Core Control Panel.
 *
 * The mock core's own admin route, not this module's business data — every dial here lives
 * in-memory on the mock and resets to all-off on restart. Every write is a PUT of just the one
 * field that changed (the backend's own partial-update contract), so two operators toggling
 * different dials never race each other's unrelated fields.
 */
export default function CoreControlPanelScreen() {
  const [dials, setDials] = useState(null);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState({});

  const reload = useCallback(async () => {
    try {
      setDials(await api.getDials());
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

  const update = useCallback(async (field, value) => {
    setSaving((prev) => ({ ...prev, [field]: true }));
    setError(null);
    try {
      const next = await api.updateDials({ [field]: value });
      setDials(next);
    } catch (e) {
      setError(e.message);
    } finally {
      setSaving((prev) => ({ ...prev, [field]: false }));
    }
  }, []);

  if (!dials) {
    return (
      <>
        <PageHeader
          title="Core Control Panel"
          lede="make the mock core misbehave on demand — slow, failing, dead, or worse: succeeding silently"
        />
        {error && (
          <Alert tone="negative" title="Could not load dials">
            {error}
          </Alert>
        )}
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Core Control Panel"
        lede="make the mock core misbehave on demand — slow, failing, dead, or worse: succeeding silently"
        badge={
          <StatusPill tone={dials.killSwitch ? 'negative' : 'positive'}>
            {dials.killSwitch ? 'Core unreachable' : 'Core healthy'}
          </StatusPill>
        }
      />

      {error && (
        <Alert tone="negative" title="Dial update failed">
          {error}
        </Alert>
      )}

      <Stack gap={6}>
        <Card title="Latency and failure" subtitle="applies to every probe and open call">
          <Stack gap={5}>
            <Slider
              label="Latency"
              suffix="ms"
              min={0}
              max={5000}
              step={100}
              value={dials.latencyMs}
              disabled={saving.latencyMs}
              onChange={(e) => setDials((prev) => ({ ...prev, latencyMs: Number(e.target.value) }))}
              onMouseUp={(e) => update('latencyMs', Number(e.target.value))}
              onTouchEnd={(e) => update('latencyMs', Number(e.target.value))}
            />
            <Slider
              label="Failure rate"
              suffix="%"
              min={0}
              max={1}
              step={0.05}
              value={dials.failureRate}
              disabled={saving.failureRate}
              onChange={(e) => setDials((prev) => ({ ...prev, failureRate: Number(e.target.value) }))}
              onMouseUp={(e) => update('failureRate', Number(e.target.value))}
              onTouchEnd={(e) => update('failureRate', Number(e.target.value))}
            />
          </Stack>
        </Card>

        <Card
          title="Switches"
          subtitle="kill switch stops every call; timeout trap creates the account, then never replies"
        >
          <Stack gap={3}>
            <Checkbox
              label="Kill switch — every core call errors"
              checked={dials.killSwitch}
              disabled={saving.killSwitch}
              onChange={(e) => update('killSwitch', e.target.checked)}
            />
            <Checkbox
              label="Timeout trap — succeeds, but the caller sees a timeout"
              checked={dials.timeoutTrap}
              disabled={saving.timeoutTrap}
              onChange={(e) => update('timeoutTrap', e.target.checked)}
            />
          </Stack>
        </Card>
      </Stack>
    </>
  );
}
