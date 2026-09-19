CREATE TABLE IF NOT EXISTS processed_job (
    job_id VARCHAR(100) PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS app_user (
    job_id VARCHAR(100) NOT NULL REFERENCES processed_job(job_id),
    item_index INTEGER NOT NULL,
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    PRIMARY KEY (job_id, item_index)
);
