const fs = require('node:fs');
const { MongoClient, ObjectId } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');

function loadMongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envText = fs.readFileSync('.env', 'utf8');
  const line = envText.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from the environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

async function main() {
  const client = new MongoClient(loadMongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const db = client.db(databaseName);
    const products = db.collection('products');
    const orders = db.collection('orders');
    const legacyProductCount = await products.countDocuments({ vendor: { $exists: true } });
    const legacyOrderCount = await orders.countDocuments({ 'items.vendor': { $exists: true } });
    const sellers = await db.collection('users').find({
      role: 'ROLE_SELLER', shopName: { $type: 'string' }
    }).project({ _id: 1, shopName: 1 }).toArray();
    console.log(JSON.stringify({ databaseName, legacyProductCount, legacyOrderCount, sellersFound: sellers.length, apply }));
    if (!apply) {
      console.log('Dry run only. Review counts, back up the database, then re-run with --apply to migrate.');
      return;
    }

    let productRowsUpdated = 0;
    for (const seller of sellers) {
      const sellerId = String(seller._id);
      const ids = [sellerId];
      if (ObjectId.isValid(sellerId)) ids.push(new ObjectId(sellerId));
      const result = await products.updateMany(
        { sellerId: { $in: ids }, vendor: { $exists: true } },
        { $set: { sellerName: seller.shopName }, $unset: { vendor: '' } });
      productRowsUpdated += result.modifiedCount;
    }

    // For platform or orphaned legacy products, preserve the old display value.
    const renamed = await products.updateMany(
      { vendor: { $exists: true }, sellerName: { $exists: false } },
      { $rename: { vendor: 'sellerName' } });
    const cleaned = await products.updateMany(
      { vendor: { $exists: true }, sellerName: { $exists: true } },
      { $unset: { vendor: '' } });

    let orderRowsUpdated = 0;
    const cursor = orders.find({ 'items.vendor': { $exists: true } }).project({ _id: 1, items: 1 });
    for await (const order of cursor) {
      const items = (order.items || []).map(item => {
        if (!Object.prototype.hasOwnProperty.call(item, 'vendor')) return item;
        const migrated = { ...item, sellerName: item.sellerName ?? item.vendor };
        delete migrated.vendor;
        return migrated;
      });
      const result = await orders.updateOne({ _id: order._id }, { $set: { items } });
      orderRowsUpdated += result.modifiedCount;
    }

    const remainingProducts = await products.countDocuments({ vendor: { $exists: true } });
    const remainingOrderItems = await orders.countDocuments({ 'items.vendor': { $exists: true } });
    console.log(JSON.stringify({
      productRowsUpdated,
      orphanProductRowsRenamed: renamed.modifiedCount,
      productRowsCleaned: cleaned.modifiedCount,
      orderRowsUpdated,
      remainingProducts,
      remainingOrderItems
    }));
    if (remainingProducts || remainingOrderItems) throw new Error('Legacy vendor fields remain after migration.');
  } finally {
    await client.close();
  }
}

main().catch(error => {
  console.error(`${error.name}: ${error.message}`);
  process.exitCode = 1;
});
