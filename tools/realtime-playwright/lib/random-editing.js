export function createRandomActionPool(actions) {
  const pool = [];
  for (const [name, weight] of Object.entries(actions)) {
    if (typeof weight !== 'number' || !Number.isFinite(weight) || weight < 0) {
      throw new Error(`Random editing action weight ${name} must be a non-negative number.`);
    }
    for (let i = 0; i < weight; i++) {
      pool.push(name);
    }
  }
  if (!pool.length) {
    throw new Error('At least one random editing action must have a positive weight.');
  }
  return pool;
}

export function pickRandomAction(pool, random = Math.random) {
  return pool[Math.floor(random() * pool.length)];
}

export function randomInteger(min, max, random = Math.random) {
  return Math.floor(random() * (max - min + 1)) + min;
}
