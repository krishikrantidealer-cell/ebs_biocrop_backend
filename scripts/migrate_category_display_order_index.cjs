const { MongoClient } = require('mongodb');

const DATABASE = 'seller_hub';
const COLLECTION = 'categories';
const INDEX_NAME = 'category_parent_sort_idx';
const EXPECTED_OLD_KEYS = { parentId: 1, sortOrder: 1 };
const TARGET_KEYS = { parentId: 1, displayOrder: 1 };
const apply = process.argv.includes('--apply');

function sameKeys(left, right) {
  return JSON.stringify(Object.entries(left)) === JSON.stringify(Object.entries(right));
}

async function main() {
  const uri = process.env.MONGODB_URI;
  if (!uri) throw new Error('MONGODB_URI must be configured (for local .env use: node --env-file=.env ...).');
  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const collection = client.db(DATABASE).collection(COLLECTION);
    const docs = await collection.countDocuments({});
    if (docs === 0) throw new Error('Category collection is empty; refusing index migration.');
    const displayOrderCount = await collection.countDocuments({ displayOrder: { $exists: true } });
    const sortOrderCount = await collection.countDocuments({ sortOrder: { $exists: true } });
    if (displayOrderCount !== docs || sortOrderCount !== 0) {
      throw new Error(`Unexpected category fields: documents=${docs}, displayOrder=${displayOrderCount}, sortOrder=${sortOrderCount}. No changes made.`);
    }

    const existing = (await collection.indexes()).find(index => index.name === INDEX_NAME);
    if (existing && sameKeys(existing.key, TARGET_KEYS)) {
      process.stdout.write(JSON.stringify({ mode: apply ? 'apply' : 'dry-run', index: INDEX_NAME,
        currentKeys: existing.key, targetKeys: TARGET_KEYS, action: 'Already aligned; no changes needed.' }, null, 2) + '\n');
      return;
    }
    if (!existing || !sameKeys(existing.key, EXPECTED_OLD_KEYS) || existing.unique) {
      throw new Error(`Existing index does not match the known safe migration source. No changes made.`);
    }

    if (!apply) {
      process.stdout.write(JSON.stringify({ mode: 'dry-run', index: INDEX_NAME,
        currentKeys: existing.key, targetKeys: TARGET_KEYS,
        action: 'No database changes made. Pass --apply to replace this non-unique index.' }, null, 2) + '\n');
      return;
    }
    await collection.dropIndex(INDEX_NAME);
    await collection.createIndex(TARGET_KEYS, { name: INDEX_NAME });
    process.stdout.write(JSON.stringify({ mode: 'apply', index: INDEX_NAME,
      previousKeys: existing.key, currentKeys: TARGET_KEYS }, null, 2) + '\n');
  } finally {
    await client.close();
  }
}

main().catch(error => {
  process.stderr.write(`${error.name}: ${error.message}\n`);
  process.exitCode = 1;
});
