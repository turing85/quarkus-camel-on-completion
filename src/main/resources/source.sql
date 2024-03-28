CREATE TABLE data(
    id BIGINT PRIMARY KEY,
    data TEXT NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO data(id, data)
  VALUES (1, 'foo'), (2, 'bar'), (3, 'baz');