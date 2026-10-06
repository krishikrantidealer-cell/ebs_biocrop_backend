const { MongoClient } = require('mongodb');

const databaseName = 'seller_hub';
const collectionName = 'orders';
const apply = process.argv.includes('--apply');

const validator = {
  $and: [
    {
      $jsonSchema: {
        bsonType: 'object',
        required: ['orderStatus', 'paymentMethod', 'paymentState', 'advancePercentage', 'advanceAmountDue', 'payment'],
        properties: {
          orderStatus: {
            bsonType: 'string',
            enum: ['ORDERED', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RTO_SHIPPED', 'RTO_DELIVERED', 'HOLD']
          },
          paymentMethod: { bsonType: 'string', enum: ['PREPAID', 'PARTIAL'] },
          paymentState: { bsonType: 'string', enum: ['AWAITING_PAYMENT', 'PAYMENT_CONFIRMATION_PENDING', 'PAYMENT_FAILED', 'PAYMENT_CONFIRMED'] },
          advancePercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 1, maximum: 100 },
          advanceAmountDue: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          paymentStatus: {
            bsonType: ['string', 'null'],
            enum: ['PAID', 'PARTIALLY_PAID', null]
          },
          payment: {
            bsonType: 'object',
            required: ['amountPaid', 'advanceAmount', 'remainingAmount', 'paidPercentage', 'remainingPercentage'],
            properties: {
              amountPaid: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
              advanceAmount: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
              remainingAmount: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
              paidPercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0, maximum: 100 },
              remainingPercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0, maximum: 100 }
            }
          }
        }
      }
    },
    {
      $or: [
        { $and: [{ paymentMethod: 'PREPAID' }, { advancePercentage: 100 }] },
        { $and: [{ paymentMethod: 'PARTIAL' }, { advancePercentage: { $gte: 1, $lte: 99 } }] }
      ]
    },
    {
      $or: [
        { $and: [{ paymentState: { $in: ['AWAITING_PAYMENT', 'PAYMENT_CONFIRMATION_PENDING', 'PAYMENT_FAILED'] } }, { paymentStatus: null }] },
        { $and: [{ paymentState: 'PAYMENT_CONFIRMED' }, { paymentStatus: { $in: ['PAID', 'PARTIALLY_PAID'] } }] }
      ]
    }
  ]
};

const previousValidator = {
  $jsonSchema: {
    bsonType: 'object',
    properties: {
      orderStatus: {
        bsonType: 'string',
        enum: ['ORDERED', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RTO_SHIPPED', 'RTO_DELIVERED', 'HOLD']
      },
      paymentMethod: { bsonType: 'string', enum: ['PREPAID', 'PARTIAL'] },
      paymentState: { bsonType: 'string', enum: ['AWAITING_PAYMENT', 'PAYMENT_CONFIRMATION_PENDING', 'PAYMENT_FAILED', 'PAYMENT_CONFIRMED'] },
      advancePercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 1, maximum: 100 },
      advanceAmountDue: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
      paymentStatus: {
        bsonType: ['string', 'null'],
        enum: ['PAID', 'PARTIALLY_PAID', null]
      },
      payment: {
        bsonType: 'object',
        properties: {
          amountPaid: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          advanceAmount: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          remainingAmount: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          paidPercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0, maximum: 100 },
          remainingPercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0, maximum: 100 }
        }
      }
    }
  }
};

const legacyValidator = {
  $jsonSchema: {
    bsonType: 'object',
    properties: {
      orderStatus: {
        bsonType: 'string',
        enum: ['ORDERED', 'CONFIRMED', 'PACKED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RTO_SHIPPED', 'RTO_DELIVERED', 'HOLD']
      },
      paymentMethod: { bsonType: 'string', enum: ['PREPAID', 'PARTIAL'] },
      paymentStatus: {
        bsonType: ['string', 'null'],
        enum: ['PAID', 'PARTIALLY_PAID', null]
      },
      payment: {
        bsonType: 'object',
        properties: {
          amountPaid: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          advanceAmount: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          remainingAmount: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0 },
          paidPercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0, maximum: 100 },
          remainingPercentage: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: 0, maximum: 100 }
        }
      }
    }
  }
};

async function main() {
  const uri = process.env.MONGODB_URI;
  if (!uri) throw new Error('MONGODB_URI must be configured in the process environment.');

  const client = new MongoClient(uri, { serverSelectionTimeoutMS: 15000 });
  try {
    await client.connect();
    const db = client.db(databaseName);
    const collectionInfo = await db.listCollections({ name: collectionName }, { nameOnly: false }).toArray();
    if (collectionInfo.length !== 1) throw new Error(`Collection ${databaseName}.${collectionName} must already exist.`);

    const existingValidator = collectionInfo[0].options?.validator;
    const validatorJson = existingValidator ? JSON.stringify(existingValidator) : null;
    const validatorMatchesCurrent = validatorJson === JSON.stringify(validator);
    const validatorMatchesPrevious = validatorJson === JSON.stringify(previousValidator)
      || validatorJson === JSON.stringify(legacyValidator);
    if (existingValidator && !validatorMatchesCurrent && !validatorMatchesPrevious) {
      throw new Error('An existing collection validator was found. Refusing to replace or merge it automatically.');
    }

    if (validatorMatchesCurrent) {
      process.stdout.write('The requested validator is already applied; no database changes made.\n');
      return;
    }

    const count = await db.collection(collectionName).countDocuments();
    if (count !== 0) throw new Error(`Expected an empty orders collection before schema setup; found ${count} documents.`);

    if (!apply) {
      process.stdout.write(JSON.stringify({
        mode: 'dry-run', database: databaseName, collection: collectionName, documentCount: count,
        existingValidator: existingValidator || null, proposedValidator: validator,
        action: 'No database changes made. Pass --apply to apply the validator.'
      }, null, 2) + '\n');
      return;
    }

    await db.command({ collMod: collectionName, validator, validationLevel: 'strict', validationAction: 'error' });
    process.stdout.write(`Applied strict order schema validation to ${databaseName}.${collectionName}; the collection was empty.\n`);
  } finally {
    await client.close();
  }
}

main().catch(error => {
  process.stderr.write(`${error.name}: ${error.message}\n`);
  process.exitCode = 1;
});
