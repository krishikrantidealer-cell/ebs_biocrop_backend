const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const fields = [
  'amountYouWillReceive', 'commissionPercentage', 'courierCharges',
  'paymentMethod', 'isPrepaidAllowed', 'isPartialPaymentAllowed',
  'minimumPartialPaymentPercentage', 'codCharge'
];

function mongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envFile = fs.readFileSync('.env', 'utf8');
  const line = envFile.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

function normalizeVariant(variant) {
  const next = { ...variant };
  if (Object.hasOwn(next, 'discountRs')) {
    if (Object.hasOwn(next, 'discountOnMrpRs') && next.discountOnMrpRs !== next.discountRs) {
      throw new Error(`Conflicting discount fields in variant ${String(variant._id || variant.id)}.`);
    }
    if (!Object.hasOwn(next, 'discountOnMrpRs')) next.discountOnMrpRs = next.discountRs;
    delete next.discountRs;
  }
  if (!Object.hasOwn(next, 'amountYouWillReceive')) next.amountYouWillReceive = null;
  if (!Object.hasOwn(next, 'commissionPercentage')) next.commissionPercentage = null;
  if (!Object.hasOwn(next, 'courierCharges')) next.courierCharges = null;
  if (!Object.hasOwn(next, 'paymentMethod')) next.paymentMethod = 'ONLINE';
  if (!Object.hasOwn(next, 'isPrepaidAllowed')) next.isPrepaidAllowed = true;
  if (!Object.hasOwn(next, 'isPartialPaymentAllowed')) next.isPartialPaymentAllowed = true;
  if (!Object.hasOwn(next, 'minimumPartialPaymentPercentage')) next.minimumPartialPaymentPercentage = 10.0;
  if (!Object.hasOwn(next, 'codCharge')) next.codCharge = 30.0;
  return next;
}

async function main() {
  const client = new MongoClient(mongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const products = client.db(databaseName).collection('products');
    const docs = await products.find({}, { projection: { variants: 1 } }).toArray();
    const fieldCounts = Object.fromEntries(fields.map(field => [field, { present: 0, missing: 0 }]));
    let variantCount = 0;
    let oldDiscountFieldCount = 0;
    const previews = [];
    const plans = docs.map(product => {
      const variants = (product.variants || []).map(variant => {
        variantCount++;
        for (const field of fields) {
          if (Object.hasOwn(variant, field)) fieldCounts[field].present++;
          else fieldCounts[field].missing++;
        }
        if (Object.hasOwn(variant, 'discountRs')) oldDiscountFieldCount++;
        const migrated = normalizeVariant(variant);
        if (previews.length < 2) previews.push({
          productId: String(product._id), variantId: String(variant._id || variant.id),
          before: Object.fromEntries(['displayRate', 'printedMrp', 'discountRs', 'gstPercentage'].map(k => [k, variant[k] ?? null])),
          after: Object.fromEntries(['amountYouWillReceive', 'commissionPercentage', 'courierCharges',
            'paymentMethod', 'isPrepaidAllowed', 'isPartialPaymentAllowed',
            'minimumPartialPaymentPercentage', 'codCharge',
            'discountOnMrpRs', 'discountRs'].map(k => [k, migrated[k] ?? null]))
        });
        return migrated;
      });
      return { _id: product._id, originalVariants: product.variants || [], variants };
    });

    console.log(JSON.stringify({ mode: apply ? 'apply' : 'dry-run', database: databaseName,
      collection: 'products', productCount: docs.length, variantCount,
      newFieldCounts: fieldCounts, oldDiscountRsCount: oldDiscountFieldCount,
      defaultsForExistingVariants: { paymentMethod: 'ONLINE', isPrepaidAllowed: true,
        isPartialPaymentAllowed: true, minimumPartialPaymentPercentage: 10.0, codCharge: 30.0 },
      pricingInputsAwaitingSellerOrAdmin: ['amountYouWillReceive', 'commissionPercentage', 'courierCharges'],
      note: 'Existing displayRate, printedMrp, GST, cost price and discount values are preserved; no prices are recalculated.',
      preview: previews }, null, 2));
    if (!apply) return;

    let modified = 0;
    for (const plan of plans) {
      // Compare the complete variant snapshot so an admin edit made after the
      // initial read cannot be overwritten by this migration.
      const result = await products.updateOne(
        { _id: plan._id, variants: plan.originalVariants },
        { $set: { variants: plan.variants } }
      );
      if (result.matchedCount !== 1) {
        throw new Error(`Product ${plan._id} changed during migration; stopped to avoid overwriting concurrent edits.`);
      }
      modified += result.modifiedCount;
    }
    const post = await products.find({}, { projection: { variants: 1 } }).toArray();
    let failures = 0;
    for (const product of post) for (const variant of product.variants || []) {
      if (Object.hasOwn(variant, 'discountRs') || fields.some(field => !Object.hasOwn(variant, field))
          || !Object.hasOwn(variant, 'discountOnMrpRs')) failures++;
    }
    console.log(JSON.stringify({ modifiedProducts: modified, variantsWithSchemaMismatch: failures }));
    if (failures) throw new Error('Post-migration schema verification failed.');
  } finally {
    await client.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
