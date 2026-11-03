const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');
const fieldsToRemove = [
  'isVerified', 'isBlocked', 'isDeleted', 'whatsappSequence',
  'productCodeSequence', 'orderNumberSequence'
];
const sellerDataFields = [
  'shopName', 'sellerCode', 'kycStatus', 'gstNumber', 'licenceImage', 'gstCertificate',
  'panCard', 'aadhaarCard', 'shopImage', 'monthlyTarget', 'preferredLanguage', 'bankDetails', 'settlementTerms'
];

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
    const users = client.db(databaseName).collection('users');
    const adminQuery = { role: 'ROLE_ADMIN' };
    const adminCount = await users.countDocuments(adminQuery);
    const fieldsPresent = {};
    for (const field of fieldsToRemove) {
      fieldsPresent[field] = await users.countDocuments({ ...adminQuery, [field]: { $exists: true } });
    }
    const blockedAdmins = await users.countDocuments({ ...adminQuery, isBlocked: true });
    const deletedAdmins = await users.countDocuments({ ...adminQuery, isDeleted: true });
    const adminRowsWithSellerData = await users.countDocuments({
      ...adminQuery,
      $or: sellerDataFields.map(field => ({ [field]: { $exists: true, $ne: null } }))
    });
    console.log(JSON.stringify({ databaseName, adminCount, fieldsPresent, blockedAdmins,
      deletedAdmins, adminRowsWithSellerData, apply }));
    if (!apply) {
      console.log('Dry run only. No documents changed.');
      return;
    }
    if (blockedAdmins || deletedAdmins || adminRowsWithSellerData) {
      throw new Error('Refusing to apply: admin lifecycle state or seller-specific data needs review first.');
    }
    const unset = Object.fromEntries(fieldsToRemove.map(field => [field, '']));
    const result = await users.updateMany(adminQuery, { $unset: unset });
    console.log(JSON.stringify({ matched: result.matchedCount, modified: result.modifiedCount }));
  } finally {
    await client.close();
  }
}

main().catch(error => {
  console.error(`${error.name}: ${error.message}`);
  process.exitCode = 1;
});
