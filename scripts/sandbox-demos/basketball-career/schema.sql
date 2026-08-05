PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS players (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE CHECK(length(name) BETWEEN 1 AND 24),
    position TEXT NOT NULL CHECK(position IN ('控球后卫', '得分后卫', '小前锋', '大前锋', '中锋')),
    archetype TEXT NOT NULL CHECK(archetype IN ('球场指挥官', '关键先生', '攻防一体', '空间型内线', '禁区守护者')),
    current_season INTEGER NOT NULL DEFAULT 1 CHECK(current_season BETWEEN 1 AND 30),
    overall INTEGER NOT NULL DEFAULT 68 CHECK(overall BETWEEN 40 AND 99),
    fans INTEGER NOT NULL DEFAULT 1200 CHECK(fans >= 0),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS games (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    season INTEGER NOT NULL CHECK(season BETWEEN 1 AND 30),
    game_number INTEGER NOT NULL CHECK(game_number > 0),
    opponent TEXT NOT NULL CHECK(length(opponent) BETWEEN 1 AND 40),
    result TEXT NOT NULL CHECK(result IN ('W', 'L')),
    points INTEGER NOT NULL CHECK(points BETWEEN 0 AND 100),
    rebounds INTEGER NOT NULL CHECK(rebounds BETWEEN 0 AND 40),
    assists INTEGER NOT NULL CHECK(assists BETWEEN 0 AND 40),
    grade REAL NOT NULL CHECK(grade BETWEEN 0 AND 10),
    story TEXT NOT NULL CHECK(length(story) BETWEEN 1 AND 300),
    played_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(player_id, season, game_number)
);

CREATE INDEX IF NOT EXISTS idx_games_player_id ON games(player_id, id DESC);

CREATE TABLE IF NOT EXISTS career_notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id INTEGER NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    content TEXT NOT NULL CHECK(length(content) BETWEEN 1 AND 8000),
    model TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_career_notes_player_id ON career_notes(player_id, id DESC);
