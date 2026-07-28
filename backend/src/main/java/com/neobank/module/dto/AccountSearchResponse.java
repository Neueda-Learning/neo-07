package com.neobank.module.dto;

import java.util.List;

/**
 * The Account Board's search response (UC-01 AC1, AC2).
 *
 * <p>{@code hasMore} is the "more — refine your search" flag: {@code results} is capped at 10,
 * newest first, and this is {@code true} only when an 11th-or-later match exists. The UI never
 * learns the exact total beyond the cap — refining the query is the only way to see more.</p>
 */
public record AccountSearchResponse(List<AccountSearchResult> results, boolean hasMore) {

    public static final AccountSearchResponse EMPTY = new AccountSearchResponse(List.of(), false);
}
