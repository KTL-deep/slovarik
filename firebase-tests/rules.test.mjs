import { before, after, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc, collection, getDocs } from 'firebase/firestore';

let env;
const id = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const device = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb';
const record = (overrides = {}) => ({ schema: 1, id, kind: 'folders', stamp: 1, device, deleted: false, fields: { name: 'Words', deletedAt: 0 }, ...overrides });
const ref = (db, uid = 'alice') => doc(db, `users/${uid}/records/${id}`);
before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Tests must run against the local Firebase emulator, never production');
  env = await initializeTestEnvironment({ projectId: 'demo-slovarik-sync', firestore: { rules: readFileSync('../firestore.rules', 'utf8') } });
});
beforeEach(async () => { await env.clearFirestore(); });
after(async () => { await env?.cleanup(); });

test('owner can create/read/list own records', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(ref(db), record()));
  await assertSucceeds(getDoc(ref(db)));
  await assertSucceeds(getDocs(collection(db, 'users/alice/records')));
});
test('anonymous and another account cannot access records', async () => {
  const alice = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(ref(alice), record()));
  for (const db of [env.unauthenticatedContext().firestore(), env.authenticatedContext('bob').firestore()]) {
    await assertFails(getDoc(ref(db)));
    await assertFails(setDoc(ref(db), record({ stamp: 2 })));
    await assertFails(getDocs(collection(db, 'users/alice/records')));
  }
});
test('invalid envelope, kind and stale revision are rejected', async () => {
  const db = env.authenticatedContext('alice').firestore();
  for (const invalid of [{ schema: 99 }, { id: 'other' }, { kind: 'admin' }, { stamp: -1 }, { device: '' }, { secret: 'unexpected' }]) {
    await assertFails(setDoc(ref(db), record(invalid)));
  }
  await assertSucceeds(setDoc(ref(db), record({ stamp: 10 })));
  await assertFails(setDoc(ref(db), record({ stamp: 9 })));
  await assertFails(setDoc(ref(db), record({ stamp: 11, kind: 'cards' })));
});
test('permanent deletion is a retained irreversible tombstone', async () => {
  const db = env.authenticatedContext('alice').firestore();
  await assertSucceeds(setDoc(ref(db), record()));
  await assertFails(deleteDoc(ref(db)));
  await assertSucceeds(setDoc(ref(db), record({ stamp: 2, deleted: true, fields: {} })));
  await assertFails(setDoc(ref(db), record({ stamp: 3 })));
  await assertFails(setDoc(ref(db), record({ stamp: 4, deleted: true })));
});
test('progress ids and collection boundaries are enforced', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const progressId = `progress-${id}`;
  await assertFails(setDoc(doc(db, `users/alice/records/${progressId}`), record({ id: progressId, kind: 'progress', fields: { card: id } })));
  await assertSucceeds(setDoc(doc(db, `users/alice/records/${progressId}`), record({ id: progressId, kind: 'progress', fields: { card: id, reps: 0, interval: 0, ease: 2.5, due: 0, step: 0 } })));
  await assertFails(setDoc(doc(db, 'users/alice/profile/settings'), { admin: true }));
  await assertFails(setDoc(doc(db, 'public/words'), record()));
});

test('profile settings are typed, private, and cannot change keys', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const setting = (fields, stamp = 1) => record({ kind: 'settings', fields, stamp });
  await assertSucceeds(setDoc(ref(db), setting({ key: 'reminder', value: { enabled: true, hour: 19, minute: 30 } })));
  await assertFails(setDoc(ref(db), setting({ key: 'reminder', value: { enabled: true, hour: 24, minute: 30 } }, 2)));
  await assertFails(setDoc(ref(db), setting({ key: 'interface_language', value: 'en' }, 3)));
  await assertFails(getDoc(ref(env.authenticatedContext('bob').firestore())));
  const otherId = 'cccccccccccccccccccccccccccccccc';
  const other = doc(db, `users/alice/records/${otherId}`);
  for (const fields of [{ key: 'randomDirection', value: 'true' }, { key: 'interface_language', value: 'xx' }, { key: 'seeded', value: true }]) {
    await assertFails(setDoc(other, { ...setting(fields), id: otherId }));
  }
  await assertSucceeds(setDoc(other, { ...setting({ key: 'randomDirection', value: true }), id: otherId }));
});

test('Match records keep the minimum score, including zero', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const match = (seconds, stamp) => record({ kind: 'match', stamp, fields: { deck: device, seconds } });
  await assertSucceeds(setDoc(ref(db), match(42, 1)));
  await assertSucceeds(setDoc(ref(db), match(0, 2)));
  await assertFails(setDoc(ref(db), match(10, 3)));
  await assertFails(setDoc(ref(db), match(-1, 4)));
  await assertSucceeds(setDoc(ref(db), record({ kind: 'match', stamp: 5, deleted: true, fields: {} })));
  await assertFails(setDoc(ref(db), match(0, 6)));
});

test('undo can be cleared and replaced, but never permanently tombstoned', async () => {
  const db = env.authenticatedContext('alice').firestore();
  const fields = { available: true, card: id, event: device,
    beforeReps: 0, beforeInterval: 0, beforeEase: 2.5, beforeDue: 0, beforeStep: 0,
    afterReps: 1, afterInterval: 1, afterEase: 2.5, afterDue: 86400000, afterStep: 0 };
  await assertSucceeds(setDoc(ref(db), record({ kind: 'undo', fields })));
  await assertSucceeds(setDoc(ref(db), record({ kind: 'undo', stamp: 2, fields: { available: false } })));
  await assertSucceeds(setDoc(ref(db), record({ kind: 'undo', stamp: 3, fields })));
  await assertFails(setDoc(ref(db), record({ kind: 'undo', stamp: 4, deleted: true, fields: {} })));
  await assertFails(setDoc(ref(db), record({ kind: 'undo', stamp: 5, fields: { ...fields, afterStep: 99 } })));
});
