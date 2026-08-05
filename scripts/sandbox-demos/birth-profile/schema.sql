CREATE TABLE IF NOT EXISTS birth_profiles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    person_name TEXT NOT NULL,
    birth_date TEXT NOT NULL,
    birth_time TEXT NOT NULL,
    birth_place TEXT NOT NULL
);
