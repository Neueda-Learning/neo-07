// This module's vocabulary, mapped onto the design system's five tones — once, here, so no
// screen ever guesses what colour a status is.
//
// The design system deliberately knows no business words (design-system/DESIGN.md § "Tones"):
// ten modules speak ten vocabularies over one contract, and a Badge that knew "ACCEPTED" would
// have to learn "VERIFIED", "CLEAR" and "SIGNED" too.
import { TONES, toneMapper } from './design-system';

// account_record.outcome — the module's real vocabulary; every screen filters on this.
export const outcomeTone = toneMapper({
  OPENED: TONES.POSITIVE,
  FAILED: TONES.NEGATIVE,
  IN_PROGRESS: TONES.INFO,
});

export const OUTCOMES = ['IN_PROGRESS', 'OPENED', 'FAILED'];

// UC-06's two failure modes — both an alarm, but distinct enough to read differently at a glance.
export const duplicateKindTone = toneMapper({
  CORE_DUPLICATE: TONES.NEGATIVE,
  MISSING_AT_CORE: TONES.WARNING,
});

export function time(iso) {
  return iso ? new Date(iso).toLocaleTimeString() : '—';
}

const MONEY = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP', maximumFractionDigits: 0 });

export function money(amount) {
  return amount == null ? '—' : MONEY.format(amount);
}
