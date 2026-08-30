-- Deliberately NOT part of init.sql. init.sql runs once, against an empty table, at container
-- creation. HNSW has no separate batch "build" step the way a B-tree's initial sort-and-build
-- does - the index is grown by inserting rows into the graph one at a time, and each insert makes
-- a locally-greedy connection decision that is never retroactively re-optimized as more data
-- arrives. A graph built by incremental single-row inserts (exactly what happens if this index
-- existed from row zero and real traffic filled the table afterward) measurably underperforms
-- one built by a single CREATE INDEX pass over an already-populated table - worse recall for the
-- same ef_search, not just a theoretical concern.
--
-- Running this after cache_entries has real rows means the initial build does that single-pass
-- construction once; only incremental inserts after this point pay the smaller per-row cost.
-- CONCURRENTLY avoids taking an exclusive lock, which is also why this belongs as a deliberate,
-- separately-run operational step against a live database rather than bootstrap schema SQL.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cache_embedding_hnsw
    ON cache_entries USING hnsw (embedding vector_cosine_ops);
