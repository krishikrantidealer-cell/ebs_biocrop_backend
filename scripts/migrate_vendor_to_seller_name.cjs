const fs = require('node:fs');
const { MongoClient, ObjectId } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const ordersOnly = process.argv.includes('--orders-only');
const clearUnresolvedProducts = process.argv.includes('--clear-unresolved-products');

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
    const allSellerProfiles = await db.collection('users').find({ role: 'ROLE_SELLER' })
      .project({ _id: 1, shopName: 1 }).toArray();
    const sellers = allSellerProfiles.filter(seller =>
      typeof seller.shopName === 'string' && seller.shopName.trim().length > 0);
    const sellersMissingShopName = allSellerProfiles.filter(seller =>
      typeof seller.shopName !== 'string' || seller.shopName.trim().length === 0);
    const toMongoIds = seller => {
      const id = String(seller._id);
      return ObjectId.isValid(id) ? [id, new ObjectId(id)] : [id];
    };
    const allSellerIds = allSellerProfiles.flatMap(toMongoIds);
    const namedSellerIds = sellers.flatMap(toMongoIds);
    const productsMissingSellerId = await products.countDocuments({
      $or: [{ sellerId: { $exists: false } }, { sellerId: null }, { sellerId: '' }]
    });
    const productsWithUnknownSellerId = await products.countDocuments({
      sellerId: { $exists: true, $nin: [null, '', ...allSellerIds] }
    });
    let productsWithSellerMissingShopName = 0;
    for (const seller of sellersMissingShopName) {
      productsWithSellerMissingShopName += await products.countDocuments({
        sellerId: { $in: toMongoIds(seller) }
      });
    }
    const productsWithoutSeller = productsMissingSellerId + productsWithUnknownSellerId
      + productsWithSellerMissingShopName;
    const unknownSellerIds = productsWithUnknownSellerId
      ? await products.distinct('sellerId', { sellerId: { $exists: true, $nin: [null, '', ...allSellerIds] } })
      : [];
    const incompleteSellerIds = productsWithSellerMissingShopName
      ? sellersMissingShopName.flatMap(seller => toMongoIds(seller))
      : [];
    const unresolvedSellerIds = [...unknownSellerIds, ...incompleteSellerIds];
    const unresolvedProductSummary = unresolvedSellerIds.length
      ? await products.aggregate([
          { $match: { sellerId: { $in: unresolvedSellerIds } } },
          { $group: {
            _id: '$sellerId', productCount: { $sum: 1 },
            storedNames: { $addToSet: { $ifNull: ['$sellerName', '$vendor'] } }
          } },
          { $limit: 10 }
        ]).toArray()
      : [];
    let productsNeedingRefresh = 0;
    for (const seller of sellers) {
      const id = String(seller._id);
      const ids = ObjectId.isValid(id) ? [id, new ObjectId(id)] : [id];
      productsNeedingRefresh += await products.countDocuments({
        sellerId: { $in: ids }, sellerName: { $ne: seller.shopName }
      });
    }
    console.log(JSON.stringify({ databaseName, legacyProductCount, legacyOrderCount,
      productsNeedingRefresh, productsMissingSellerId, productsWithUnknownSellerId,
      productsWithSellerMissingShopName,
      unknownSellerIdSamples: unknownSellerIds.slice(0, 10).map(String),
      sellerIdsMissingShopName: sellersMissingShopName.map(seller => String(seller._id)),
      unresolvedProductSummary,
      sellerProfiles: sellers.map(seller => ({ id: String(seller._id), shopName: seller.shopName })),
      sellerCount: allSellerProfiles.length, sellersWithShopName: sellers.length, apply }));
    if (!apply) {
      console.log('Dry run only. Review counts and back up the database before applying. Use --apply --orders-only for order snapshots or --apply --clear-unresolved-products to remove stale names only from products without a seller profile.');
      return;
    }
    if (ordersOnly) {
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
      const remainingOrderItems = await orders.countDocuments({ 'items.vendor': { $exists: true } });
      console.log(JSON.stringify({ orderRowsUpdated, remainingOrderItems, ordersOnly: true }));
      if (remainingOrderItems) throw new Error('Legacy vendor fields remain in order items after migration.');
      return;
    }
    if (clearUnresolvedProducts) {
      if (!unresolvedSellerIds.length) {
        console.log(JSON.stringify({ productsCleared: 0, unresolvedSellerIds: 0 }));
        return;
      }
      const result = await products.updateMany(
        { sellerId: { $in: unresolvedSellerIds } },
        { $unset: { sellerName: '', vendor: '' } });
      const remainingUnknownNames = await products.countDocuments({
        sellerId: { $in: unresolvedSellerIds },
        $or: [{ sellerName: { $exists: true } }, { vendor: { $exists: true } }]
      });
      console.log(JSON.stringify({ productsCleared: result.modifiedCount,
        unresolvedSellerIds: unresolvedSellerIds.length, remainingUnknownNames }));
      if (remainingUnknownNames) throw new Error('Stored seller-name snapshots remain for unresolved sellers.');
      return;
    }
    if (productsWithoutSeller) {
      throw new Error(`Refusing to apply: ${productsWithoutSeller} products have no matching seller record; resolve them before migration.`);
    }

    let productRowsUpdated = 0;
    for (const seller of sellers) {
      const sellerId = String(seller._id);
      const ids = ObjectId.isValid(sellerId) ? [sellerId, new ObjectId(sellerId)] : [sellerId];
      const result = await products.updateMany(
        { sellerId: { $in: ids } },
        { $set: { sellerName: seller.shopName }, $unset: { vendor: '' } });
      productRowsUpdated += result.modifiedCount;
    }

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
