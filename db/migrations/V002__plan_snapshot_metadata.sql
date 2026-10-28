-- Preserve generation settings and finalization metadata with each plan version.

ALTER TABLE plan ADD COLUMN generation_config_snapshot TEXT NOT NULL DEFAULT '{}';
ALTER TABLE plan ADD COLUMN change_summary TEXT NOT NULL DEFAULT 'Migrated from an earlier schema';
ALTER TABLE plan ADD COLUMN finalized_by TEXT;
