const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');

function mongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envFile = fs.readFileSync('.env', 'utf8');
  const line = envFile.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

function dimensionList(variant, productDimensions) {
  const value = variant?.dimensions;
  if (Array.isArray(value)) return value;
  if (value && typeof value === 'object') return [value];
  if (productDimensions && typeof productDimensions === 'object') return [productDimensions];
  return [{ length: 20, width: 10, height: 10, unit: 'cm' }];
}

function nonEmptyImages(images) {
  return Array.isArray(images) && images.some(image => image != null && String(image).trim() !== '');
}

async function main() {
  const client = new MongoClient(mongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const products = client.db(databaseName).collection('products');
    const docs = await products.find({}, { projection: { images: 1, productImages: 1, dimensions: 1, variants: 1 } }).toArray();
    const imageConflicts = docs.filter(product => nonEmptyImages(product.images));
    const productDimensionCount = docs.filter(product => product.dimensions != null).length;
    let variantsToConvert = 0;
    let variantsToDefault = 0;
    const plans = docs.map(product => {
      const variants = Array.isArray(product.variants) ? product.variants : [];
      const migratedVariants = variants.map(variant => {
        if (!Array.isArray(variant?.dimensions)) variantsToConvert++;
        if ((!variant?.dimensions || (Array.isArray(variant.dimensions) && variant.dimensions.length === 0))
            && product.dimensions == null) variantsToDefault++;
        return { ...variant, dimensions: dimensionList(variant, product.dimensions) };
      });
      return { _id: product._id, productImages: Array.isArray(product.productImages) ? product.productImages : [],
        variants: migratedVariants, hasLegacyImages: Object.hasOwn(product, 'images'),
        hasProductDimensions: Object.hasOwn(product, 'dimensions'),
        hasMissingProductImages: !Object.hasOwn(product, 'productImages') };
    });

    console.log(JSON.stringify({ mode: apply ? 'apply' : 'dry-run', database: databaseName,
      collection: 'products', productCount: docs.length,
      documentsWithLegacyImages: docs.filter(p => Object.hasOwn(p, 'images')).length,
      documentsWithNonEmptyLegacyImages: imageConflicts.length,
      imageConflictSamples: imageConflicts.slice(0, 5).map(p => ({ id: String(p._id), images: p.images })),
      documentsWithProductLevelDimensions: productDimensionCount,
      variantsToConvertToDimensionArrays: variantsToConvert,
      variantsReceivingDefaultDimension: variantsToDefault,
      documentsMissingProductImagesArray: plans.filter(p => p.hasMissingProductImages).length,
      productDimensionSamples: docs.filter(p => p.dimensions != null).slice(0, 5).map(p => ({
        id: String(p._id), dimensions: p.dimensions,
        variantDimensions: (p.variants || []).map(v => v?.dimensions).slice(0, 5)
      }))
    }, null, 2));

    if (!apply) return;
    if (imageConflicts.length) {
      throw new Error('Non-empty legacy images were found. No writes made; map them into productImages before removing images.');
    }
    let updated = 0;
    for (const plan of plans) {
      const set = { productImages: plan.productImages };
      plan.variants.forEach((variant, index) => { set[`variants.${index}.dimensions`] = variant.dimensions; });
      const unset = {};
      if (plan.hasLegacyImages) unset.images = '';
      if (plan.hasProductDimensions) unset.dimensions = '';
      await products.updateOne({ _id: plan._id }, { $set: set, ...(Object.keys(unset).length ? { $unset: unset } : {}) });
      updated++;
    }
    const remainingLegacy = await products.countDocuments({ $or: [{ images: { $exists: true } }, { dimensions: { $exists: true } }] });
    const badVariantDimensions = await products.countDocuments({ variants: { $elemMatch: { dimensions: { $not: { $type: 'array' } } } } });
    const missingImagesArray = await products.countDocuments({ productImages: { $not: { $type: 'array' } } });
    console.log(JSON.stringify({ appliedDocuments: updated, remainingLegacyProductFields: remainingLegacy,
      variantsWithNonArrayDimensions: badVariantDimensions, productsWithoutProductImagesArray: missingImagesArray }));
    if (remainingLegacy || badVariantDimensions || missingImagesArray) throw new Error('Post-migration schema verification failed.');
  } finally {
    await client.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
