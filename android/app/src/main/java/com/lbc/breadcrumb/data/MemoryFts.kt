package com.lbc.breadcrumb.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * Full-text index over a memory's searchable text -- the same fields as
 * [Memory.searchableText], so what local search matches is what gets embedded
 * later. Source app is deliberately absent: it is a filter (step 4.3), not text.
 *
 * External content: the index stores no copy of the text. Room keeps it in step
 * with `memories` through triggers, which is why [MemoryDao.upsert] must update
 * in place -- a REPLACE deletes without firing them.
 *
 * FTS4 because Room only supports FTS3/4, and it has no bm25; ranking is left to
 * the server's hybrid search (step 4.4). unicode61 rather than porter: porter
 * stems English but folds case in ASCII only, while unicode61 folds case and
 * accents everywhere. Prefix queries ([FtsQuery]) cover most of what stemming
 * would.
 */
@Fts4(contentEntity = Memory::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "memories_fts")
data class MemoryFts(
    val title: String?,
    val note: String?,
    val summary: String?,
    val rawText: String?,
    val extractedText: String?,
)
