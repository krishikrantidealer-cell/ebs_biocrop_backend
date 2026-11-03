const fs = require('node:fs');
const { MongoClient } = require('mongodb');

const databaseName = process.env.MONGODB_DATABASE || 'seller_hub';
const apply = process.argv.includes('--apply');

function loadMongoUri() {
  if (process.env.MONGODB_URI) return process.env.MONGODB_URI;
  const text = fs.readFileSync('.env', 'utf8');
  const line = text.split(/\r?\n/).find(value => /^\s*MONGODB_URI\s*=/.test(value));
  if (!line) throw new Error('MONGODB_URI is missing from the environment and .env.');
  return line.replace(/^\s*MONGODB_URI\s*=\s*/, '').trim().replace(/^['"]|['"]$/g, '');
}

const hasText = value => typeof value === 'string' && value.trim().length > 0;

function missingProfileFields(user) {
  const missing = [];
  if (!hasText(user.firstName)) missing.push('firstName');
  if (!hasText(user.lastName)) missing.push('lastName');
  if (!hasText(user.shopName)) missing.push('shopName');
  const address = user.address;
  if (!address || !hasText(address.villageArea)) missing.push('address.villageArea');
  if (!address || !hasText(address.address2)) {
    missing.push('address.address2');
  }
  if (!address || !hasText(address.cityTehsil)) missing.push('address.cityTehsil');
  if (!address || !hasText(address.state)) missing.push('address.state');
  if (!address || typeof address.pincode !== 'string' || !/^[0-9]{6}$/.test(address.pincode)) {
    missing.push('address.pincode');
  }
  return missing;
}

async function main() {
  const client = new MongoClient(loadMongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const users = client.db(databaseName).collection('users');
    const sellers = await users.find({ role: 'ROLE_SELLER' }).toArray();
    const inconsistent = sellers
      .map(user => ({ user, missing: missingProfileFields(user) }))
      .filter(row => Boolean(row.user.isProfileComplete) && row.missing.length > 0);
    console.log(JSON.stringify({
      databaseName,
      sellerCount: sellers.length,
      incompleteProfilesMarkedComplete: inconsistent.length,
      affected: inconsistent.map(({ user, missing }) => ({ id: String(user._id), missing })),
      apply
    }));
    if (!apply) {
      console.log('Dry run only. No documents changed.');
      return;
    }
    const ids = inconsistent.map(({ user }) => user._id);
    const result = ids.length
      ? await users.updateMany(
          { _id: { $in: ids }, role: 'ROLE_SELLER', isProfileComplete: true },
          { $set: { isProfileComplete: false, updatedAt: new Date() } })
      : { matchedCount: 0, modifiedCount: 0 };
    const remaining = (await users.find({ role: 'ROLE_SELLER' }).toArray())
      .filter(user => Boolean(user.isProfileComplete) && missingProfileFields(user).length > 0);
    console.log(JSON.stringify({ matched: result.matchedCount, modified: result.modifiedCount,
      remainingInconsistentSellerProfiles: remaining.length }));
    if (remaining.length) throw new Error('Some incomplete seller profiles are still marked complete.');
  } finally {
    await client.close();
  }
}

main().catch(error => {
  console.error(`${error.name}: ${error.message}`);
  process.exitCode = 1;
});
