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

const bankDefaults = () => ({ accountHolderName: null, bankName: null, accountNumber: null,
  ifscCode: null, branch: null, upiId: null, isVerified: false });
const settlementDefaults = () => ({ commissionPercentage: 0, settlementCycle: null, payoutStatus: null });

function missingFields(document, defaults) {
  const set = {};
  for (const [key, value] of Object.entries(defaults)) {
    if (!Object.hasOwn(document, key) || document[key] === undefined || (document[key] === null && value !== null)) set[key] = value;
  }
  return set;
}

function normalizeNested(current, defaults) {
  const normalized = { ...defaults };
  if (current && typeof current === 'object' && !Array.isArray(current)) {
    for (const [key, value] of Object.entries(current)) {
      if (value !== null || !Object.hasOwn(defaults, key) || defaults[key] === null) normalized[key] = value;
    }
  }
  return normalized;
}

function sellerDefaults() {
  return {
    phoneNumber: null, email: null, passwordHash: null, passwordResetTokenHash: null,
    passwordResetExpiresAt: null, firstName: null, lastName: null, shopName: null,
    role: 'ROLE_SELLER', status: 'ACTIVE', isBlocked: false, isDeleted: false,
    isVerified: false, isProfileComplete: false, gstNumber: null, licenceImage: [],
    gstCertificate: null, panCard: null, aadhaarCard: null,
    kycStatus: null, isKycComplete: false, productCodeSequence: 0,
    orderNumberSequence: 0, monthlyTarget: null, preferredLanguage: null, notes: null,
    notesHistory: [], address: null, shippingAddresses: [], whatsappSequence: 0,
    createdAt: null, updatedAt: null
  };
}

function customerDefaults() {
  return {
    phoneNumber: null, email: null, passwordHash: null, firstName: null, lastName: null,
    role: 'ROLE_CUSTOMER', isVerified: false, isProfileComplete: false, isKycComplete: false,
    shippingAddresses: [], address: null, isBlocked: false, isDeleted: false, status: 'ACTIVE',
    whatsappSequence: 0, notes: null, notesHistory: [], createdAt: null, updatedAt: null
  };
}

async function main() {
  const client = new MongoClient(loadMongoUri(), { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const users = client.db(databaseName).collection('users');
    const summaries = [];
    for (const [role, defaults] of [['ROLE_SELLER', sellerDefaults()], ['ROLE_CUSTOMER', customerDefaults()]]) {
      const cursor = users.find({ role });
      let scanned = 0;
      let changed = 0;
      for await (const row of cursor) {
        scanned++;
        const set = missingFields(row, defaults);
        if (role === 'ROLE_SELLER') {
          const bank = normalizeNested(row.bankDetails, bankDefaults());
          const terms = normalizeNested(row.settlementTerms, settlementDefaults());
          if (JSON.stringify(bank) !== JSON.stringify(row.bankDetails)) set.bankDetails = bank;
          if (JSON.stringify(terms) !== JSON.stringify(row.settlementTerms)) set.settlementTerms = terms;
        }
        if (Object.keys(set).length > 0) {
          changed++;
          if (apply) await users.updateOne({ _id: row._id, role }, { $set: set });
        }
      }
      summaries.push({ role, scanned, wouldChange: changed, applied: apply ? changed : 0 });
    }
    console.log(JSON.stringify({ databaseName, apply, summaries }, null, 2));
    if (!apply) console.log('Dry run only. No documents changed. Re-run with --apply after reviewing the counts.');
  } finally {
    await client.close();
  }
}

main().catch(error => {
  console.error(`${error.name}: ${error.message}`);
  process.exitCode = 1;
});
