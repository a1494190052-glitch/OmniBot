const state = { player: null, games: [], latest: null };
const $ = (id) => document.getElementById(id);
const show = (id) => $(id).classList.remove('hidden');
const hide = (id) => $(id).classList.add('hidden');
const opponents = ['北岸鲸群', '山城烈焰', '海港飞梭', '首都引擎', '西部拓荒者', '湾区雷鸟'];

function seedFor(text) {
  let value = 2166136261;
  for (const char of text) value = Math.imul(value ^ char.charCodeAt(0), 16777619);
  return Math.abs(value >>> 0);
}

function pick(seed, min, max) { return min + (seed % (max - min + 1)); }
function formatNumber(value) { return Number(value || 0).toLocaleString('zh-CN'); }
function round(value) { return Math.round(value * 10) / 10; }

async function loadCareer() {
  const players = await window.omni.tools.call('list_players', { _order_by: 'id DESC', _limit: 1 });
  if (!players.rows.length) {
    hide('loading'); show('setup'); return;
  }
  state.player = players.rows[0];
  const games = await window.omni.tools.call('list_games', {
    player_id: state.player.id, _order_by: 'id DESC', _limit: 100,
  });
  state.games = games.rows;
  const notes = await window.omni.tools.call('list_notes', {
    player_id: state.player.id, _order_by: 'id DESC', _limit: 1,
  });
  if (notes.rows.length) $('coach-output').textContent = notes.rows[0].content;
  hide('loading'); show('career'); render();
}

async function createPlayer(event) {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button');
  const name = $('player-name').value.trim();
  if (!name) return;
  button.disabled = true;
  $('setup-error').textContent = '';
  try {
    await window.omni.tools.call('create_player', {
      name,
      position: $('player-position').value,
      archetype: $('player-archetype').value,
      overall: 68,
      fans: 1200,
    });
    hide('setup'); show('loading');
    await loadCareer();
  } catch (error) {
    $('setup-error').textContent = error.message || '创建失败，请换一个球员名。';
  } finally { button.disabled = false; }
}

function simulateBoxScore(gameNumber) {
  const player = state.player;
  const seed = seedFor(`${player.name}:${player.position}:${player.current_season}:${gameNumber}`);
  const base = Math.max(4, player.overall - 52);
  const guard = player.position.includes('后卫');
  const center = player.position === '中锋' || player.position === '大前锋';
  const points = Math.min(54, base + pick(seed, 2, 17));
  const rebounds = Math.min(22, pick(seed >>> 3, center ? 6 : 2, center ? 16 : 10));
  const assists = Math.min(20, pick(seed >>> 6, guard ? 5 : 1, guard ? 14 : 8));
  const impact = points + rebounds * 1.3 + assists * 1.5 + player.overall / 4;
  const win = impact + pick(seed >>> 9, -16, 16) >= 50;
  const grade = round(Math.min(10, Math.max(5.5, 5.2 + impact / 24 + (win ? .35 : 0))));
  const opponent = opponents[seed % opponents.length];
  const story = win
    ? `${player.name}在末节稳住节奏，带队拿下了这场虚构胜利。`
    : `${player.name}打出个人表现，但球队在这场虚构对局中惜败。`;
  return { opponent, result: win ? 'W' : 'L', points, rebounds, assists, grade, story };
}

async function simulateGame() {
  const button = $('simulate-button');
  button.disabled = true;
  button.innerHTML = '正在模拟… <span>···</span>';
  try {
    const gameNumber = state.games.length + 1;
    const box = simulateBoxScore(gameNumber);
    const game = {
      player_id: state.player.id,
      season: state.player.current_season,
      game_number: gameNumber,
      ...box,
    };
    await window.omni.tools.call('record_game', game);
    const nextOverall = Math.min(99, state.player.overall + (gameNumber % 3 === 0 ? 1 : 0));
    const nextFans = state.player.fans + box.points * (box.result === 'W' ? 18 : 8);
    await window.omni.tools.call('update_player', {
      id: state.player.id,
      overall: nextOverall,
      fans: nextFans,
    });
    state.player = { ...state.player, overall: nextOverall, fans: nextFans };
    state.latest = { id: Date.now(), ...game };
    state.games.unshift(state.latest);
    render();
  } catch (error) {
    $('latest-game').innerHTML = `<p>模拟没有保存：${escapeHtml(error.message || String(error))}</p>`;
    show('latest-game');
  } finally {
    button.disabled = false;
    button.innerHTML = '模拟下一场 <span>开球 →</span>';
  }
}

async function askCoach() {
  const button = $('coach-button');
  button.disabled = true;
  $('coach-output').textContent = '小万正在阅读你的真实存档…';
  try {
    const recent = state.games.slice(0, 5).map((game) =>
      `第${game.game_number}场 ${game.result} ${game.points}分 ${game.rebounds}篮板 ${game.assists}助攻 评分${game.grade}`
    ).join('\n') || '还没有比赛记录';
    const result = await window.omni.tools.call('career_advice', {
      player: `${state.player.name}，${state.player.position}，${state.player.archetype}，能力值${state.player.overall}`,
      recent_games: recent,
    });
    $('coach-output').textContent = result.text;
    await window.omni.tools.call('record_note', {
      player_id: state.player.id,
      content: result.text,
      model: result.model || '',
    });
  } catch (error) {
    $('coach-output').textContent = `建议生成失败，比赛存档不受影响。${error.message || ''}`;
  } finally { button.disabled = false; }
}

function render() {
  const player = state.player;
  const games = state.games;
  const wins = games.filter((game) => game.result === 'W').length;
  const average = games.length ? games.reduce((sum, game) => sum + Number(game.points), 0) / games.length : 0;
  $('player-title').textContent = player.name;
  $('season-number').textContent = player.current_season;
  $('overall').textContent = player.overall;
  $('position').textContent = player.position;
  $('archetype').textContent = player.archetype;
  $('fans').textContent = formatNumber(player.fans);
  $('games-count').textContent = games.length;
  $('avg-points').textContent = average.toFixed(1);
  $('win-rate').textContent = games.length ? `${Math.round(wins / games.length * 100)}%` : '0%';
  $('record-summary').textContent = `${wins} 胜 ${games.length - wins} 负`;
  $('game-list').innerHTML = games.length ? games.slice(0, 12).map(gameRow).join('') : '<div class="empty-row">签下合同后，模拟你的第一场比赛。</div>';
  if (state.latest) {
    $('latest-game').innerHTML = latestGame(state.latest);
    show('latest-game');
  }
}

function latestGame(game) {
  return `<div class="result"><span>${game.result === 'W' ? '胜利' : '惜败'} · 第 ${game.game_number} 场</span><span>vs ${escapeHtml(game.opponent)}</span></div>
    <div class="boxscore"><span><b>${game.points}</b>得分</span><span><b>${game.rebounds}</b>篮板</span><span><b>${game.assists}</b>助攻</span></div>
    <p>${escapeHtml(game.story)} 比赛已写入独立生涯存档。</p>`;
}

function gameRow(game) {
  return `<div class="game-row ${game.result === 'W' ? 'win' : ''}">
    <span class="badge">${game.result}</span><div><b>vs ${escapeHtml(game.opponent)}</b><small>${game.points}分 · ${game.rebounds}板 · ${game.assists}助</small></div><span class="grade">${Number(game.grade).toFixed(1)}</span>
  </div>`;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, (char) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[char]));
}

$('player-form').addEventListener('submit', createPlayer);
$('simulate-button').addEventListener('click', simulateGame);
$('coach-button').addEventListener('click', askCoach);
loadCareer().catch((error) => {
  $('loading').innerHTML = `<p>生涯存档载入失败：${escapeHtml(error.message || String(error))}</p>`;
});
