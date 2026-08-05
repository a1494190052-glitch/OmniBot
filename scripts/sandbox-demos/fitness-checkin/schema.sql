CREATE TABLE IF NOT EXISTS workouts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exercise TEXT NOT NULL,
    weight REAL NOT NULL,
    repetitions INTEGER NOT NULL,
    recorded_at TEXT NOT NULL
);
