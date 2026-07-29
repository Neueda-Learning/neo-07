import React, { useState } from 'react';
import { EmptyState, PageHeader, SearchInput, Toolbar } from '../design-system';
import CaseDetailScreen from './CaseDetailScreen.jsx';

/**
 * UC-02/UC-03's entry point: an id-search box that opens the Account Detail screen. Deliberately
 * minimal — a full board with name search is UC-01's own screen (Account Board); this one exists
 * so Case Detail has somewhere to be reached from in this branch.
 *
 * `initialApplicationId` lets another screen deep-link straight into a case (e.g. the Duplicate
 * Report's "view case" — UC-06 AC#6) without this screen needing to know who's linking to it.
 */
export default function CasesScreen({ initialApplicationId }) {
  const [query, setQuery] = useState(initialApplicationId ?? '');
  const [applicationId, setApplicationId] = useState(initialApplicationId ?? null);

  const onSubmit = (e) => {
    e.preventDefault();
    const id = query.trim();
    if (id) {
      setApplicationId(id);
    }
  };

  return (
    <>
      <PageHeader
        title="Account Detail"
        lede="look up one case by its application id — the anchor record, its attempt log, and the applicant"
      />

      <Toolbar>
        <form onSubmit={onSubmit} style={{ display: 'contents' }}>
          <SearchInput
            grow
            placeholder="Application id"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Look up an application id"
          />
        </form>
      </Toolbar>

      {applicationId ? (
        <CaseDetailScreen applicationId={applicationId} />
      ) : (
        <EmptyState title="Search for an application id to begin">
          Type an <strong>application id</strong> above — press Enter to open its case detail.
        </EmptyState>
      )}
    </>
  );
}
