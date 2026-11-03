const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');

function loadLocalMongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envText = fs.readFileSync('.env', 'utf8');
  const line = envText.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from the environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

async function main() {
  const client = new MongoClient(loadLocalMongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const products = client.db(databaseName).collection('products');
    const oldOnly = { ratings: { $exists: true }, averageRating: { $exists: false } };
    const both = { ratings: { $exists: true }, averageRating: { $exists: true } };
    const oldOnlyCount = await products.countDocuments(oldOnly);
    const bothCount = await products.countDocuments(both);
    console.log(JSON.stringify({ databaseName, oldOnlyCount, bothCount, apply }));
    if (!apply) {
      console.log('Dry run only. Re-run with --apply to migrate product rating fields.');
      return;
    }

    const renamed = await products.updateMany(oldOnly, { $rename: { ratings: 'averageRating' } });
    const cleaned = await products.updateMany(both, { $unset: { ratings: '' } });
    const remaining = await products.countDocuments({ ratings: { $exists: true } });
    console.log(JSON.stringify({ renamed: renamed.modifiedCount, cleaned: cleaned.modifiedCount, remainingLegacyFields: remaining }));
    if (remaining !== 0) throw new Error('Legacy ratings fields remain after migration.');
  } finally {
    await client.close();
  }
}

main().catch(error => {
  console.error(`${error.name}: ${error.message}`);
  process.exitCode = 1;
});
