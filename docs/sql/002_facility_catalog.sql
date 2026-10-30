-- Run scripts/ApplyFacilityMigration.java preflight before applying.
-- IDs match the existing application's Long/BIGINT identity strategy.
CREATE TABLE IF NOT EXISTS location (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    region VARCHAR(20), name VARCHAR(100), facility_name VARCHAR(100), contact VARCHAR(20),
    latitude DECIMAL(10,8), longtitude DECIMAL(11,8),
    created_at TIMESTAMP(6) NULL, updated_at TIMESTAMP(6) NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS service (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    location_id BIGINT NOT NULL,
    name VARCHAR(255), status VARCHAR(255), start_time DATETIME(6), end_time DATETIME(6),
    is_free CHAR(1), url VARCHAR(200), cancel_deadline_at DATE,
    created_at TIMESTAMP(6) NULL, updated_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_service_location FOREIGN KEY (location_id) REFERENCES location(id)
) ENGINE=InnoDB;

ALTER TABLE `match` ADD CONSTRAINT fk_match_service FOREIGN KEY (service_id) REFERENCES service(id);
