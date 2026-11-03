const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const registryName = 'seller_sku_identities';

function mongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const text = fs.readFileSync('.env', 'utf8');
  const line = text.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

function id(value) { return value == null ? null : String(value); }
function numberFromCode(code) {
  const match = /^S(\d+)$/.exec(code || '');
  return match ? Number(match[1]) : 0;
}
function maxSkuNumber(sku) {
  const match = /^S(\d+)-P/.exec(sku || '');
  return match ? Number(match[1]) : 0;
}

async function main() {
  const client = new MongoClient(mongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const db = client.db(databaseName);
    const users = db.collection('users');
    const products = db.collection('products');
    const orders = db.collection('orders');
    const registryExists = (await db.listCollections({ name: registryName }, { nameOnly: true }).toArray()).length > 0;
    const registry = registryExists ? db.collection(registryName) : null;
    const sellers = await users.find({ role: 'ROLE_SELLER' }).toArray();
    const oldEntries = registry ? await registry.find({ _id: { $ne: '__seller_number_counter__' } }).toArray() : [];
    const oldCounter = registry ? await registry.findOne({ _id: '__seller_number_counter__' }) : null;
    const bySellerId = new Map(oldEntries.map(entry => [id(entry._id), Number(entry.sellerNumber)]));
    const usedNumbers = oldEntries.map(entry => Number(entry.sellerNumber));
    if (usedNumbers.some(number => !Number.isInteger(number) || number < 1) || new Set(usedNumbers).size !== usedNumbers.length) {
      throw new Error('The old seller-code registry contains invalid or duplicate assignments; no changes made.');
    }

    let highest = Math.max(0, ...usedNumbers, Number(oldCounter?.nextSellerNumber || 0));
    const existingCodes = sellers.map(seller => seller.sellerCode).filter(Boolean);
    const existingNumbers = existingCodes.map(numberFromCode);
    if (existingNumbers.some(number => !number) || new Set(existingCodes).size !== existingCodes.length) {
      throw new Error('Seller documents contain invalid or duplicate sellerCode values; no changes made.');
    }
    highest = Math.max(highest, ...existingNumbers);
    for await (const product of products.find({}, { projection: { sellerId: 1, sku: 1 } })) highest = Math.max(highest, maxSkuNumber(product.sku));
    if (await db.listCollections({ name: 'orders' }, { nameOnly: true }).hasNext()) {
      for await (const order of orders.find({}, { projection: { 'items.sku': 1 } })) {
        for (const item of order.items || []) highest = Math.max(highest, maxSkuNumber(item.sku));
      }
    }

    const seenCodes = new Map();
    const plans = [];
    const sellerIds = new Set(sellers.map(seller => id(seller._id)));
    for (const seller of sellers) {
      const sellerId = id(seller._id);
      const priorNumber = bySellerId.get(sellerId);
      const priorCode = priorNumber ? `S${String(priorNumber).padStart(4, '0')}` : null;
      if (seller.sellerCode && priorCode && seller.sellerCode !== priorCode) {
        throw new Error(`Seller ${sellerId} has conflicting existing and registry codes; no changes made.`);
      }
      const sellerCode = seller.sellerCode || priorCode || `S${String(++highest).padStart(4, '0')}`;
      const otherSeller = seenCodes.get(sellerCode);
      if (otherSeller) throw new Error(`Seller code ${sellerCode} is assigned to multiple sellers; no changes made.`);
      seenCodes.set(sellerCode, sellerId);
      if (seller.sellerCode !== sellerCode) plans.push({ _id: seller._id, sellerId, sellerCode, version: seller.version });
    }

    const productMismatches = [];
    const codeBySellerId = new Map(sellers.map(seller => {
      const plan = plans.find(item => item.sellerId === id(seller._id));
      return [id(seller._id), seller.sellerCode || plan?.sellerCode];
    }));
    for await (const product of products.find({}, { projection: { sellerId: 1, sku: 1 } })) {
      const sellerCode = codeBySellerId.get(id(product.sellerId));
      if (!sellerCode) continue; // Preserve orphan product history; never rewrite or delete its SKU.
      if (product.sku && !product.sku.startsWith(`${sellerCode}-P`)) {
        productMismatches.push({ productId: id(product._id), sellerId: id(product.sellerId), sku: product.sku, expectedPrefix: sellerCode });
        if (productMismatches.length >= 10) break;
      }
    }
    if (productMismatches.length) {
      throw new Error(`Existing product SKU prefixes conflict with seller mappings; no writes made. Samples: ${JSON.stringify(productMismatches)}`);
    }

    const orphanRegistryEntries = oldEntries.filter(entry => !sellerIds.has(id(entry._id))).length;
    const nonSellerCodes = await users.countDocuments({ role: { $ne: 'ROLE_SELLER' }, sellerCode: { $exists: true } });
    console.log(JSON.stringify({
      mode: apply ? 'APPLY' : 'DRY_RUN', database: databaseName,
      sellerDocuments: sellers.length, sellerCodesToAdd: plans.length,
      nonSellerSellerCodeFieldsToRemove: nonSellerCodes,
      existingCodesPreserved: sellers.length - plans.length,
      orphanRegistryEntries, productSkuPrefixMismatches: 0,
      assignments: sellers.map(seller => ({ id: id(seller._id), shopName: seller.shopName || null,
        sellerCode: seller.sellerCode || plans.find(item => item.sellerId === id(seller._id))?.sellerCode || null })),
      oldRegistryExists: registryExists, oldRegistryWillBeDropped: apply && registryExists
    }, null, 2));

    if (!apply) return;
    await users.createIndex({ sellerCode: 1 }, {
      unique: true,
      partialFilterExpression: { sellerCode: { $type: 'string' } },
      name: 'uniq_seller_code'
    });
    for (const plan of plans) {
      const filter = { _id: plan._id, role: 'ROLE_SELLER', $or: [{ sellerCode: { $exists: false } }, { sellerCode: null }] };
      const result = await users.updateOne(filter, { $set: { sellerCode: plan.sellerCode }, $inc: { version: 1 } });
      if (result.matchedCount !== 1) {
        const current = await users.findOne({ _id: plan._id }, { projection: { sellerCode: 1 } });
        if (current?.sellerCode !== plan.sellerCode) throw new Error(`Concurrent seller update stopped migration at ${plan.sellerId}.`);
      }
    }
    if (nonSellerCodes) await users.updateMany({ role: { $ne: 'ROLE_SELLER' }, sellerCode: { $exists: true } }, { $unset: { sellerCode: '' }, $inc: { version: 1 } });

    const verifiedSellers = await users.find({ role: 'ROLE_SELLER' }).toArray();
    const missing = verifiedSellers.filter(seller => !seller.sellerCode);
    const duplicateCodes = await users.aggregate([
      { $match: { role: 'ROLE_SELLER', sellerCode: { $type: 'string' } } },
      { $group: { _id: '$sellerCode', count: { $sum: 1 } } },
      { $match: { count: { $gt: 1 } } }
    ]).toArray();
    if (missing.length || duplicateCodes.length || verifiedSellers.length !== sellers.length) {
      throw new Error('Post-write sellerCode verification failed; old registry was retained.');
    }
    for await (const product of products.find({}, { projection: { sellerId: 1, sku: 1 } })) {
      const sellerCode = codeBySellerId.get(id(product.sellerId));
      if (sellerCode && product.sku && !product.sku.startsWith(`${sellerCode}-P`)) {
        throw new Error(`Post-write SKU verification failed for product ${id(product._id)}; old registry was retained.`);
      }
    }
    if (registry) await registry.drop();
    const collectionsAfter = (await db.listCollections({ name: registryName }, { nameOnly: true }).toArray()).length;
    if (collectionsAfter) throw new Error('Old seller-code collection still exists after drop attempt.');
    console.log('Seller codes verified in users; old seller-code collection dropped. Existing product and order SKU values were not changed.');
  } finally {
    await client.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
