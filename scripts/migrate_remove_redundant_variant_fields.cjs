const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const fields = ['packUnit', 'totalBaseQuantity', 'totalBaseUnit'];

function mongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envFile = fs.readFileSync('.env', 'utf8');
  const line = envFile.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

async function main() {
  const client = new MongoClient(mongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const products = client.db(databaseName).collection('products');
    const docs = await products.find({}, { projection: { variants: 1 } }).toArray();
    const counts = Object.fromEntries(fields.map(field => [field, 0]));
    let variants = 0;
    for (const product of docs) for (const variant of product.variants || []) {
      variants++;
      for (const field of fields) if (Object.hasOwn(variant, field)) counts[field]++;
    }
    const documentsAffected = docs.filter(product => (product.variants || []).some(variant =>
      fields.some(field => Object.hasOwn(variant, field)))).length;
    console.log(JSON.stringify({ mode: apply ? 'apply' : 'dry-run', database: databaseName,
      collection: 'products', productCount: docs.length, variantCount: variants,
      variantFieldCounts: counts, productDocumentsAffected: documentsAffected }, null, 2));
    if (!apply) return;

    const unset = Object.fromEntries(fields.map(field => [`variants.$[].${field}`, '']));
    const result = await products.updateMany({}, { $unset: unset });
    const remaining = {};
    for (const field of fields) {
      remaining[field] = await products.countDocuments({ variants: { $elemMatch: { [field]: { $exists: true } } } });
    }
    console.log(JSON.stringify({ matchedProducts: result.matchedCount, modifiedProducts: result.modifiedCount,
      productsWithRemainingDeprecatedVariantFields: remaining }));
    if (Object.values(remaining).some(count => count !== 0)) {
      throw new Error('Post-migration verification failed.');
    }
  } finally {
    await client.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
