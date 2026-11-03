const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const bucketName = process.env.GCS_SELLERDATA_BUCKET_NAME || 'sellerhub-prod-sellerdata-media-us';

function mongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const envFile = fs.readFileSync('.env', 'utf8');
  const line = envFile.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

function migrateShape(user) {
  const update = { $set: {}, $unset: {} };
  if (Object.hasOwn(user, 'shopImage')) update.$unset.shopImage = '';
  if (user.role === 'ROLE_SELLER') {
    const current = user.licenceImage;
    const licenceImage = Array.isArray(current) ? current
      : typeof current === 'string' && current.trim() ? [current.trim()] : [];
    if (licenceImage.length > 10) throw new Error(`Seller ${user._id} has more than 10 licence files; manual review required.`);
    if (!Array.isArray(current)) update.$set.licenceImage = licenceImage;
    for (const field of ['gstCertificate', 'panCard', 'aadhaarCard']) {
      if (!Object.hasOwn(user, field)) update.$set[field] = null;
    }
    const complete = Boolean(user.gstNumber && licenceImage.length
      && user.gstCertificate && user.panCard && user.aadhaarCard);
    if (!complete && (user.kycStatus === 'APPROVED' || user.isKycComplete === true)) {
      update.$set.kycStatus = null;
      update.$set.isKycComplete = false;
    }
  } else {
    for (const field of ['licenceImage', 'gstCertificate', 'panCard', 'aadhaarCard']) {
      if (Object.hasOwn(user, field)) update.$unset[field] = '';
    }
  }
  if (!Object.keys(update.$set).length) delete update.$set;
  if (!Object.keys(update.$unset).length) delete update.$unset;
  return update;
}

async function main() {
  const client = new MongoClient(mongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const users = client.db(databaseName).collection('users');
    const docs = await users.find({}, { projection: {
      _id: 1, role: 1, version: 1, licenceImage: 1, gstNumber: 1,
      gstCertificate: 1, panCard: 1, aadhaarCard: 1, kycStatus: 1, isKycComplete: 1,
      shopImage: 1
    } }).toArray();
    const plans = docs.map(user => ({ user, update: migrateShape(user) }));
    const sellers = docs.filter(user => user.role === 'ROLE_SELLER');
    const legacyLicenceRefs = sellers.reduce((count, user) => {
      const refs = Array.isArray(user.licenceImage) ? user.licenceImage
        : typeof user.licenceImage === 'string' && user.licenceImage.trim() ? [user.licenceImage] : [];
      return count + refs.filter(ref => typeof ref !== 'string' || !ref.startsWith(`gs://${bucketName}/`)).length;
    }, 0);
    const summary = {
      mode: apply ? 'apply' : 'dry-run', database: databaseName, collection: 'users',
      userCount: docs.length, sellerCount: sellers.length,
      sellerLicenceImageStringCount: sellers.filter(user => typeof user.licenceImage === 'string').length,
      sellersMissingGstCertificate: sellers.filter(user => !user.gstCertificate).length,
      sellersMissingPanCard: sellers.filter(user => !user.panCard).length,
      sellersMissingAadhaarCard: sellers.filter(user => !user.aadhaarCard).length,
      sellerLicenceReferencesOutsideDedicatedBucket: legacyLicenceRefs,
      documentsWithShopImageToRemove: docs.filter(user => Object.hasOwn(user, 'shopImage')).length,
      sellersWhoseApprovalWillBeReset: plans.filter(({ user, update }) => user.role === 'ROLE_SELLER'
        && update.$set?.kycStatus === null && (user.kycStatus === 'APPROVED' || user.isKycComplete === true)).map(({ user }) => String(user._id)),
      note: 'This migration preserves seller file references, renames licenceImage from scalar to list, adds missing KYC document placeholders, removes shopImage, and clears approval only where required KYC documents are missing.'
    };
    console.log(JSON.stringify(summary, null, 2));
    if (!apply) return;

    let modified = 0;
    for (const { user, update } of plans) {
      if (!update.$set && !update.$unset) continue;
      const filter = { _id: user._id, role: user.role, version: user.version ?? null };
      const write = { ...update };
      if (Number.isInteger(user.version)) write.$inc = { version: 1 };
      const result = await users.updateOne(filter, write);
      if (result.matchedCount !== 1) throw new Error(`User ${user._id} changed during migration; stopped to avoid overwriting concurrent updates.`);
      modified += result.modifiedCount;
    }

    const post = await users.find({}, { projection: {
      _id: 1, role: 1, licenceImage: 1, gstCertificate: 1, panCard: 1, aadhaarCard: 1, shopImage: 1
    } }).toArray();
    const failures = post.filter(user => Object.hasOwn(user, 'shopImage') || (user.role === 'ROLE_SELLER'
      ? !Array.isArray(user.licenceImage) || user.licenceImage.length > 10
        || !Object.hasOwn(user, 'gstCertificate') || !Object.hasOwn(user, 'panCard') || !Object.hasOwn(user, 'aadhaarCard')
      : ['licenceImage', 'gstCertificate', 'panCard', 'aadhaarCard'].some(field => Object.hasOwn(user, field))));
    console.log(JSON.stringify({ modifiedUsers: modified, schemaMismatches: failures.map(user => String(user._id)) }));
    if (failures.length) throw new Error('Post-migration seller document schema verification failed.');
  } finally {
    await client.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
