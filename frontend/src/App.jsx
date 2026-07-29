import React, { useCallback, useEffect, useState } from 'react';
import { AppShell, Button, SideBrand, SideNav, StatusPill } from './design-system';
import RequestsScreen from './components/RequestsScreen.jsx';
import AccountBoardScreen from './components/AccountBoardScreen.jsx';
import CasesScreen from './components/CasesScreen.jsx';
import FailedOpensQueueScreen from './components/FailedOpensQueueScreen.jsx';
import DuplicateReportScreen from './components/DuplicateReportScreen.jsx';
import CoreControlPanelScreen from './components/CoreControlPanelScreen.jsx';
import CoreConfigScreen from './components/CoreConfigScreen.jsx';
import { api } from './api.js';

const POLL_MS = 2000;
const HEALTH_MS = 10000;

const TEAM_MEMBERS = ['Geoff', 'Rick', 'Carrie', 'Coco'];

/**
 * The team's footer block. An outlined ("hollow") wordmark over the member names — belongs to us,
 * not to the design system, so it lives here and is styled in `src/styles.css` with `--ds-*`
 * variables only (no hex, no new deps). The letters are stroke-only (`fill: none`) in the deep
 * text ink so the pale glass shows through them; the "4" is the one solid glyph, filled volt and
 * ink-outlined to pop as the brand mark. All colours are theme tokens, so it re-tints with the
 * palette.
 */
function TeamWordmark() {
  return (
    <svg
      className="app-team-mark"
      viewBox="0 0 230 34"
      height="26"
      role="img"
      aria-label="Trans4mer"
    >
      <text x="115" y="24" textAnchor="middle" className="app-team-mark__text">
        TRANS<tspan className="app-team-mark__accent">4</tspan>MER
      </text>
    </svg>
  );
}

/**
 * The team's footer block: the wordmark over the member names.
 */
function TeamFooter() {
  return (
    <div className="app-team-footer">
      <TeamWordmark />
      <div className="app-team-members">{TEAM_MEMBERS.join(' · ')}</div>
    </div>
  );
}

/**
 * The screens in the side menu.
 *
 * ⚠️ One real screen and three placeholders — the placeholders are there so the menu shows you
 * where your own screens go, and they are `disabled` so nobody clicks into nothing. Replace them
 * with what your business topic actually needs; the operator UI is a graded deliverable, and a
 * single read-only list is not one.
 */
const SCREENS = [
  { id: 'applications', label: 'Applications' },
  { id: 'accounts', label: 'Account Board' },
  { id: 'cases', label: 'Account Detail' },
  { id: 'failed-opens', label: 'Failed-Opens Queue' },
  { id: 'duplicates', label: 'Duplicate Report' },
  { id: 'core-panel', label: 'Core Control Panel' },
  { id: 'core-config', label: 'Core Configuration' },
];

/**
 * A sidebar rather than a top bar: this app is expected to grow more screens than a row of tabs
 * holds, and the menu is where a team plans that growth. The identity box above it is the only
 * place the app says who it belongs to — its values come from `/info`, so the same image reads
 * "Team 07" once SERVICE_TEAM says so.
 */
export default function App() {
  const [screen, setScreen] = useState('applications');
  const [requests, setRequests] = useState([]);
  const [error, setError] = useState(null);
  const [health, setHealth] = useState(null);
  const [info, setInfo] = useState(null);

  const reload = useCallback(async () => {
    try {
      setRequests(await api.listApplications());
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    if (screen !== 'applications') return undefined;

    reload();
    const id = setInterval(reload, POLL_MS);
    return () => clearInterval(id);
  }, [reload, screen]);

  const refreshHealth = useCallback(async () => {
    try {
      const [h, i] = await Promise.all([api.health(), api.info()]);
      setHealth(h);
      setInfo(i);
    } catch {
      setHealth(null);
    }
  }, []);

  useEffect(() => {
    refreshHealth();
    const id = setInterval(refreshHealth, HEALTH_MS);
    return () => clearInterval(id);
  }, [refreshHealth]);

  const up = !error && health?.status === 'UP';

  return (
    <AppShell
      side={
        <>
          <SideBrand
            brand={info?.team ?? 'Team'}
            product={info?.service ?? 'Module'}
            meta={info ? `${info.serviceId} · ${info.domain}` : undefined}
          />
          <SideNav items={SCREENS} active={screen} onSelect={setScreen} />
          {/* Health and refresh lived in the top bar; with the bar gone they belong beside the
              menu rather than inside it — a menu item that is not a screen is a trap. */}
          <div className="app-side-status">
            <StatusPill tone={up ? 'positive' : 'negative'}>{up ? 'Up' : 'Down'}</StatusPill>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                reload();
                refreshHealth();
              }}
            >
              Refresh
            </Button>
          </div>
        </>
      }
      footer={<TeamFooter />}
    >
      {screen === 'applications' && (
        <RequestsScreen requests={requests} error={error} info={info} />
      )}
      {screen === 'accounts' && <AccountBoardScreen />}
      {screen === 'cases' && <CasesScreen />}
      {screen === 'failed-opens' && <FailedOpensQueueScreen />}
      {screen === 'duplicates' && <DuplicateReportScreen />}
      {screen === 'core-panel' && <CoreControlPanelScreen />}
      {screen === 'core-config' && <CoreConfigScreen />}
    </AppShell>
  );
}
