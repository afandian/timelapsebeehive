-- A user in the system.
CREATE TABLE user (
    id MEDIUMINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY(id),

    username VARCHAR(1024) NOT NULL,

    -- public notes
    notes text not null
);

CREATE UNIQUE INDEX id ON user(id);

-- An individual bee hive.
CREATE TABLE hive (
    id MEDIUMINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY(id),

    owner MEDIUMINT REFERENCES user(id),
    name VARCHAR(128) NOT NULL,
    notes TEXT
);

CREATE UNIQUE INDEX id ON hive(id);
CREATE UNIQUE INDEX name ON hive(owner, name);

-- A timelapse sample recording.
CREATE TABLE sample (
  id MEDIUMINT NOT NULL AUTO_INCREMENT,
  PRIMARY KEY(id),
  hive MEDIUMINT NOT NULL REFERENCES hive(id),
  timestamp BIGINT NOT NULL
);

CREATE UNIQUE INDEX timestamp on sample (hive, timestamp);