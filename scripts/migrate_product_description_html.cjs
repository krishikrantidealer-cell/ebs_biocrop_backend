const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const legacyFields = [
  'technicalContent', 'features', 'benefits', 'modeOfAction', 'suitableCrops',
  'targetPests', 'targetDiseases', 'dosage', 'applicationMethod'
];

function mongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envFile = fs.readFileSync('.env', 'utf8');
  const line = envFile.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

function escapeHtml(value) {
  return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

function hasContent(value) {
  if (Array.isArray(value)) return value.some(hasContent);
  return value != null && String(value).trim().length > 0;
}

function asHtml(value) {
  if (!hasContent(value)) return '';
  if (Array.isArray(value)) return `<ul>${value.filter(hasContent).map(item => `<li>${escapeHtml(item)}</li>`).join('')}</ul>`;
  const text = String(value);
  return /<\/?[a-z][\s\S]*>/i.test(text) ? text : `<p>${escapeHtml(text)}</p>`;
}

function sectionTitle(field) {
  return field.replace(/([A-Z])/g, ' $1').replace(/^./, letter => letter.toUpperCase());
}

function migratedDescription(product) {
  const current = typeof product.description === 'string' ? product.description : '';
  const description = current.trim()
    ? (/<\/?[a-z][\s\S]*>/i.test(current) ? current : `<p>${escapeHtml(current)}</p>`)
    : '';
  const additions = legacyFields.filter(field => hasContent(product[field]))
    .map(field => `<p><strong>${sectionTitle(field)}:</strong></p>${asHtml(product[field])}`);
  return [description, ...additions].filter(Boolean).join('\n');
}

async function main() {
  const client = new MongoClient(mongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const products = client.db(databaseName).collection('products');
    const projection = { description: 1 };
    for (const field of legacyFields) projection[field] = 1;
    const docs = await products.find({}, { projection }).toArray();
    const plans = docs.filter(product => legacyFields.some(field => Object.hasOwn(product, field)))
      .map(product => ({ _id: product._id, description: migratedDescription(product),
        populatedFields: legacyFields.filter(field => hasContent(product[field])) }));

    const remaining = new Map(legacyFields.map(field => [field, 0]));
    for (const product of docs) for (const field of legacyFields) {
      if (Object.hasOwn(product, field)) remaining.set(field, remaining.get(field) + 1);
    }
    console.log(JSON.stringify({
      mode: apply ? 'apply' : 'dry-run', database: databaseName, collection: 'products',
      productCount: docs.length, documentsWithFieldsToRemove: plans.length,
      populatedLegacyValues: plans.filter(plan => plan.populatedFields.length).length,
      legacyFieldCounts: Object.fromEntries(remaining),
      descriptionsToUpdate: plans.filter(plan => plan.populatedFields.length).length,
      fieldsToUnsetPerDocument: legacyFields.length,
      sample: plans.slice(0, 3).map(plan => ({ id: String(plan._id), populatedFields: plan.populatedFields,
        migratedDescriptionPreview: plan.description.slice(0, 500) }))
    }, null, 2));

    if (!apply) return;
    let updated = 0;
    for (const plan of plans) {
      const unset = Object.fromEntries(legacyFields.map(field => [field, '']));
      const result = await products.updateOne({ _id: plan._id }, {
        ...(plan.populatedFields.length ? { $set: { description: plan.description } } : {}),
        $unset: unset
      });
      if (result.matchedCount !== 1) throw new Error(`Product ${plan._id} disappeared during migration.`);
      updated++;
    }
    const leftovers = await products.countDocuments({ $or: legacyFields.map(field => ({ [field]: { $exists: true } })) });
    console.log(JSON.stringify({ appliedDocuments: updated, remainingDocumentsWithDeprecatedFields: leftovers }));
    if (leftovers !== 0) throw new Error('Migration completed with deprecated fields still present.');
  } finally {
    await client.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
