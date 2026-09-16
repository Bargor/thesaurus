import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  writeBatch,
} from 'firebase/firestore';

const projectId = 'demo-thesaurus';
const householdId = 'dom-1';
const day = 24 * 60 * 60 * 1000;
let env;

const db = (uid, email = `${uid}@example.test`) =>
  env.authenticatedContext(uid, { email }).firestore();
const householdRef = (database) => doc(database, 'households', householdId);
const memberRef = (database, uid) => doc(database, 'households', householdId, 'members', uid);
const entryRef = (database, id) => doc(database, 'households', householdId, 'entries', id);
const categoryRef = (database, id) => doc(database, 'households', householdId, 'categories', id);
const subcategoryRef = (database, categoryId, id) =>
  doc(database, 'households', householdId, 'categories', categoryId, 'subcategories', id);
const invitationRef = (database, id) => doc(database, 'households', householdId, 'invitations', id);
const userRef = (database, uid) => doc(database, 'users', uid);

const firstHouseholdBatch = (database, { uid, email, id }) => {
  const batch = writeBatch(database);
  batch.set(userRef(database, uid), {
    email,
    displayName: uid,
    householdId: id,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(doc(database, 'households', id), {
    name: 'Pierwszy dom',
    ownerId: uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.set(doc(database, 'households', id, 'members', uid), {
    email,
    displayName: uid,
    role: 'OWNER',
    invitationId: null,
    joinedAt: serverTimestamp(),
  });
  return batch;
};

const entry = (authorId, overrides = {}) => ({
  householdId,
  amountGrosze: -1234,
  date: '2026-09-14',
  title: null,
  categoryId: 'food',
  subcategoryId: null,
  tags: ['dom', 'zakupy'],
  authorId,
  updatedById: authorId,
  createdAt: serverTimestamp(),
  updatedAt: serverTimestamp(),
  deleted: false,
  deletedAt: null,
  deletedById: null,
  ...overrides,
});

const category = (authorId, overrides = {}) => ({
  householdId,
  name: 'Jedzenie',
  color: null,
  archived: false,
  defaultEntryType: 'EXPENSE',
  authorId,
  updatedById: authorId,
  createdAt: serverTimestamp(),
  updatedAt: serverTimestamp(),
  ...overrides,
});

const subcategory = (authorId, categoryId, overrides = {}) => ({
  householdId,
  categoryId,
  name: 'Supermarket',
  archived: false,
  authorId,
  updatedById: authorId,
  createdAt: serverTimestamp(),
  updatedAt: serverTimestamp(),
  ...overrides,
});

const invitation = (overrides = {}) => ({
  householdId,
  email: 'guest@example.test',
  invitedBy: 'alice',
  expiresAt: Timestamp.fromMillis(Date.now() + day),
  status: 'PENDING',
  acceptedBy: null,
  createdAt: serverTimestamp(),
  ...overrides,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      rules: readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });

  await env.withSecurityRulesDisabled(async (context) => {
    const admin = context.firestore();
    await setDoc(householdRef(admin), {
      name: 'Nasz dom',
      ownerId: 'alice',
      createdAt: Timestamp.now(),
      updatedAt: Timestamp.now(),
    });
    for (const [uid, role] of [['alice', 'OWNER'], ['bob', 'MEMBER'], ['charlie', 'MEMBER']]) {
      await setDoc(memberRef(admin, uid), {
        email: `${uid}@example.test`,
        displayName: uid,
        role,
        invitationId: null,
        joinedAt: Timestamp.now(),
      });
    }
    await setDoc(categoryRef(admin, 'food'), {
      householdId,
      name: 'Jedzenie',
      color: null,
      archived: false,
      defaultEntryType: 'EXPENSE',
      authorId: 'alice',
      updatedById: 'alice',
      createdAt: Timestamp.now(),
      updatedAt: Timestamp.now(),
    });
  });
});

after(async () => {
  await env.cleanup();
});

test('signed amounts persist, optional title is accepted, and type is not stored', async () => {
  const alice = db('alice');
  await assertSucceeds(setDoc(entryRef(alice, 'income'), entry('alice', { amountGrosze: 2500 })));
  await assertSucceeds(setDoc(entryRef(alice, 'expense'), entry('alice', { amountGrosze: -2500 })));
  const saved = await getDoc(entryRef(alice, 'income'));
  assert.equal(saved.data().amountGrosze, 2500);
  assert.equal(saved.data().title, null);
  assert.equal('type' in saved.data(), false);
});

test('zero, duplicate tags, non-string tags, and duplicate type fields are rejected', async () => {
  const alice = db('alice');
  await assertFails(setDoc(entryRef(alice, 'zero'), entry('alice', { amountGrosze: 0 })));
  await assertFails(setDoc(entryRef(alice, 'duplicate-tags'), entry('alice', { tags: ['dom', 'dom'] })));
  await assertFails(setDoc(entryRef(alice, 'invalid-tags'), entry('alice', { tags: [42] })));
  await assertFails(setDoc(entryRef(alice, 'stored-type'), entry('alice', { type: 'EXPENSE' })));
});

test('non-members cannot read or write household entries', async () => {
  const alice = db('alice');
  const mallory = db('mallory');
  await assertSucceeds(setDoc(entryRef(alice, 'private'), entry('alice')));
  await assertFails(getDoc(entryRef(mallory, 'private')));
  await assertFails(setDoc(entryRef(mallory, 'forbidden'), entry('mallory')));
});

test('authors and owners may edit entries but another member may not', async () => {
  const bob = db('bob');
  await assertSucceeds(setDoc(entryRef(bob, 'permissions'), entry('bob')));
  await assertSucceeds(updateDoc(entryRef(bob, 'permissions'), {
    title: 'Autorska zmiana',
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(entryRef(db('charlie'), 'permissions'), {
    title: 'Nie moja zmiana',
    updatedById: 'charlie',
    updatedAt: serverTimestamp(),
  }));
  await assertSucceeds(updateDoc(entryRef(db('alice'), 'permissions'), {
    title: 'Zmiana właściciela',
    updatedById: 'alice',
    updatedAt: serverTimestamp(),
  }));
});

test('entry authorship and creation metadata are immutable', async () => {
  const bob = db('bob');
  await assertSucceeds(setDoc(entryRef(bob, 'immutable'), entry('bob')));
  await assertFails(updateDoc(entryRef(bob, 'immutable'), {
    authorId: 'alice',
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(entryRef(bob, 'immutable'), {
    createdAt: serverTimestamp(),
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
});

test('tombstones are narrow, terminal, and cannot be physically deleted', async () => {
  const bob = db('bob');
  const reference = entryRef(bob, 'deleted');
  await assertSucceeds(setDoc(reference, entry('bob')));
  await assertFails(updateDoc(reference, {
    amountGrosze: -9999,
    deleted: true,
    deletedById: 'bob',
    deletedAt: serverTimestamp(),
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertSucceeds(updateDoc(reference, {
    deleted: true,
    deletedById: 'bob',
    deletedAt: serverTimestamp(),
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(reference, {
    deleted: false,
    deletedById: null,
    deletedAt: null,
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(reference, {
    title: 'Zmiana po usunięciu',
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(deleteDoc(reference));
});

test('taxonomy authors and owners may update while other members and hard deletes are denied', async () => {
  const bob = db('bob');
  const cat = categoryRef(bob, 'custom-food');
  await assertSucceeds(setDoc(cat, category('bob')));
  await assertSucceeds(updateDoc(cat, {
    name: 'Jedzenie domowe',
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(categoryRef(db('charlie'), 'custom-food'), {
    name: 'Przejęta kategoria',
    updatedById: 'charlie',
    updatedAt: serverTimestamp(),
  }));
  await assertSucceeds(updateDoc(categoryRef(db('alice'), 'custom-food'), {
    archived: true,
    updatedById: 'alice',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(deleteDoc(categoryRef(db('alice'), 'custom-food')));
});

test('new entries require an active matching category and subcategory', async () => {
  const alice = db('alice');
  await assertFails(setDoc(entryRef(alice, 'missing-category'), entry('alice', { categoryId: 'missing' })));

  await env.withSecurityRulesDisabled(async (context) => {
    const admin = context.firestore();
    await setDoc(categoryRef(admin, 'archived-category'), {
      ...category('alice'), archived: true, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
    });
    await setDoc(subcategoryRef(admin, 'food', 'active-child'), {
      ...subcategory('alice', 'food'), archived: false, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
    });
    await setDoc(subcategoryRef(admin, 'food', 'archived-child'), {
      ...subcategory('alice', 'food'), archived: true, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
    });
    await setDoc(subcategoryRef(admin, 'food', 'mismatched-child'), {
      ...subcategory('alice', 'other-category'), archived: false, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
    });
  });

  await assertFails(setDoc(entryRef(alice, 'archived-category'), entry('alice', { categoryId: 'archived-category' })));
  await assertSucceeds(setDoc(entryRef(alice, 'active-subcategory'), entry('alice', { subcategoryId: 'active-child' })));
  await assertFails(setDoc(entryRef(alice, 'archived-subcategory'), entry('alice', { subcategoryId: 'archived-child' })));
  await assertFails(setDoc(entryRef(alice, 'mismatched-subcategory'), entry('alice', { subcategoryId: 'mismatched-child' })));
});

test('historical entries may retain archived taxonomy but changed taxonomy must be active', async () => {
  const alice = db('alice');
  const reference = entryRef(alice, 'historical-category');
  await assertSucceeds(setDoc(reference, entry('alice')));
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(categoryRef(context.firestore(), 'food'), { archived: true });
  });
  await assertSucceeds(updateDoc(reference, {
    title: 'Opis historyczny', updatedById: 'alice', updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(reference, {
    categoryId: 'archived-category', updatedById: 'alice', updatedAt: serverTimestamp(),
  }));
  await env.withSecurityRulesDisabled(async (context) => {
    await updateDoc(categoryRef(context.firestore(), 'food'), { archived: false });
  });
});

test('categories accept both default entry types but preserve stable authorship and household', async () => {
  const bob = db('bob');
  const reference = categoryRef(bob, 'custom-income');
  await assertSucceeds(setDoc(reference, category('bob', { defaultEntryType: 'INCOME' })));
  await assertFails(updateDoc(reference, {
    authorId: 'alice', updatedById: 'bob', updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(reference, {
    householdId: 'other-house', updatedById: 'bob', updatedAt: serverTimestamp(),
  }));
  await assertFails(setDoc(categoryRef(db('charlie'), 'forged-author'), category('bob')));
});

test('subcategory author permissions and immutable category linkage are enforced', async () => {
  const bob = db('bob');
  await assertSucceeds(setDoc(categoryRef(bob, 'subcategory-parent'), category('bob')));
  const reference = subcategoryRef(bob, 'subcategory-parent', 'market');
  await assertSucceeds(setDoc(reference, subcategory('bob', 'subcategory-parent')));
  await assertSucceeds(updateDoc(reference, {
    name: 'Sklep',
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(subcategoryRef(db('charlie'), 'subcategory-parent', 'market'), {
    name: 'Nie moja',
    updatedById: 'charlie',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(reference, {
    categoryId: 'other',
    updatedById: 'bob',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(deleteDoc(reference));
});

test('subcategories require an existing parent category', async () => {
  const bob = db('bob');
  await assertFails(setDoc(
    subcategoryRef(bob, 'missing-category', 'orphan'),
    subcategory('bob', 'missing-category'),
  ));
});

test('taxonomy allows archival and restoration without changing stable ids', async () => {
  const bob = db('bob');
  const categoryDocument = categoryRef(bob, 'archiveable');
  const subcategoryDocument = subcategoryRef(bob, 'archiveable', 'child');
  await assertSucceeds(setDoc(categoryDocument, category('bob')));
  await assertSucceeds(setDoc(subcategoryDocument, subcategory('bob', 'archiveable')));
  await assertSucceeds(updateDoc(categoryDocument, { archived: true, updatedById: 'bob', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(categoryDocument, { archived: false, updatedById: 'bob', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(subcategoryDocument, { archived: true, updatedById: 'bob', updatedAt: serverTimestamp() }));
  const saved = await getDoc(subcategoryDocument);
  assert.equal(saved.id, 'child');
  assert.equal(saved.data().categoryId, 'archiveable');
});

test('users can access only their own validated profile', async () => {
  const alice = db('profile-alice');
  const user = userRef(alice, 'profile-alice');
  await assertSucceeds(firstHouseholdBatch(alice, {
    uid: 'profile-alice', email: 'profile-alice@example.test', id: 'profile-house',
  }).commit());
  await assertSucceeds(updateDoc(user, {
    displayName: 'Alicja',
    updatedAt: serverTimestamp(),
  }));
  await assertFails(getDoc(userRef(db('bob'), 'profile-alice')));
  await assertFails(updateDoc(user, {
    email: 'changed@example.test',
    updatedAt: serverTimestamp(),
  }));
});

test('initial onboarding atomically creates one owner household and starter categories', async () => {
  const newcomer = db('newcomer');
  const batch = firstHouseholdBatch(newcomer, {
    uid: 'newcomer', email: 'newcomer@example.test', id: 'newcomer-house',
  });
  batch.set(doc(newcomer, 'households', 'newcomer-house', 'categories', 'jedzenie'), {
    householdId: 'newcomer-house',
    name: 'Jedzenie',
    color: null,
    archived: false,
    defaultEntryType: 'EXPENSE',
    authorId: 'newcomer',
    updatedById: 'newcomer',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  await assertSucceeds(batch.commit());
  const membership = await getDoc(doc(newcomer, 'households', 'newcomer-house', 'members', 'newcomer'));
  assert.equal(membership.data().role, 'OWNER');
  await assertSucceeds(getDoc(doc(newcomer, 'households', 'newcomer-house', 'categories', 'jedzenie')));
});

test('a profile cannot create or join a second household', async () => {
  const single = db('single');
  await assertSucceeds(firstHouseholdBatch(single, {
    uid: 'single', email: 'single@example.test', id: 'single-house',
  }).commit());
  await assertFails(firstHouseholdBatch(single, {
    uid: 'single', email: 'single@example.test', id: 'second-house',
  }).commit());
});

test('invitations require a matching email and expire within seven days', async () => {
  const alice = db('alice');
  const reference = invitationRef(alice, 'invite-visible');
  await assertSucceeds(setDoc(reference, invitation()));
  await assertSucceeds(getDoc(invitationRef(db('guest', 'guest@example.test'), 'invite-visible')));
  await assertFails(getDoc(invitationRef(db('guest', 'other@example.test'), 'invite-visible')));
  await assertFails(getDoc(invitationRef(db('bob'), 'invite-visible')));
  await assertFails(setDoc(invitationRef(alice, 'too-long'), invitation({
    expiresAt: Timestamp.fromMillis(Date.now() + 8 * day),
  })));
});

test('recipient collection queries are denied while owners may list invitations', async () => {
  const path = (database) => collection(database, 'households', householdId, 'invitations');
  await assertFails(getDocs(path(db('guest', 'guest@example.test'))));
  await assertSucceeds(getDocs(path(db('alice'))));
});

test('only an owner can revoke a pending invitation and identity remains immutable', async () => {
  const alice = db('alice');
  const reference = invitationRef(alice, 'invite-revoke');
  await assertSucceeds(setDoc(reference, invitation()));
  await assertFails(updateDoc(invitationRef(db('bob'), 'invite-revoke'), { status: 'REVOKED' }));
  await assertFails(updateDoc(reference, { email: 'other@example.test', status: 'REVOKED' }));
  await assertSucceeds(updateDoc(reference, { status: 'REVOKED' }));
  await assertFails(updateDoc(reference, { status: 'PENDING' }));
  await assertFails(deleteDoc(reference));
});

test('accepting an invitation and creating membership must be one atomic batch', async () => {
  const alice = db('alice');
  await assertSucceeds(setDoc(invitationRef(alice, 'invite-accept'), invitation()));

  const guest = db('guest', 'guest@example.test');
  await assertFails(updateDoc(invitationRef(guest, 'invite-accept'), {
    status: 'ACCEPTED',
    acceptedBy: 'guest',
  }));

  const batch = writeBatch(guest);
  batch.set(userRef(guest, 'guest'), {
    email: 'guest@example.test',
    displayName: 'Gość',
    householdId,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });
  batch.update(invitationRef(guest, 'invite-accept'), {
    status: 'ACCEPTED',
    acceptedBy: 'guest',
  });
  batch.set(memberRef(guest, 'guest'), {
    email: 'guest@example.test',
    displayName: 'Gość',
    role: 'MEMBER',
    invitationId: 'invite-accept',
    joinedAt: serverTimestamp(),
  });
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDoc(householdRef(guest)));
  await assertSucceeds(getDoc(invitationRef(guest, 'invite-accept')));
});

test('an expired invitation cannot be read or accepted', async () => {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(invitationRef(context.firestore(), 'expired'), invitation({
      expiresAt: Timestamp.fromMillis(Date.now() - day),
      createdAt: Timestamp.fromMillis(Date.now() - 2 * day),
    }));
  });
  const guest = db('late-guest', 'guest@example.test');
  await assertFails(getDoc(invitationRef(guest, 'expired')));
  await assertFails(updateDoc(invitationRef(guest, 'expired'), {
    status: 'ACCEPTED',
    acceptedBy: 'late-guest',
  }));
});
