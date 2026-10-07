import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Read-only source contract checks. CI assembly separately verifies the actual AGP/Hilt classpaths.
const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const app = join(root, 'app');
const gradle = readFileSync(join(app, 'build.gradle.kts'), 'utf8');
const productionRegistration = gradle.match(/if \(variant\.buildType in setOf\(([^)]+)\)\)\s*\{\s*stageKotlinSources\(variant, "([^"]+)"\)/);
assert.ok(productionRegistration, 'Production staging needs an explicit build-type guard');
const productionVariants = [...productionRegistration[1].matchAll(/"([^"]+)"/g)].map(([, name]) => name);
const productionInput = productionRegistration[2];
const fixtureInputs = [...gradle.matchAll(/variant\.(hostTests|deviceTests)\.values\.forEach \{ stageKotlinSources\(it, "([^"]+)"\) \}/g)]
  .map(([, kind, input]) => ({ kind, input }));

function generatedRoot(owner, input) {
  // addGeneratedSourceDirectory assigns the physical build/generated location to this task's
  // OutputDirectory. A distinct component task identity therefore owns each physical root.
  return { owner, input, producer: `stage${owner[0].toUpperCase()}${owner.slice(1)}SharedSources` };
}

function stagedRoots(variant) {
  return productionVariants.includes(variant) ? [generatedRoot(variant, productionInput)] : [];
}

function kotlinFiles(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? kotlinFiles(path) : entry.name.endsWith('.kt') ? [path] : [];
  });
}

function selectedFiles(variant) {
  // Inspect canonical inputs without executing staging. CI assembly validates the generated
  // classpaths/dependencies; this source graph detects collisions before any task runs.
  const directories = ['src/main/java', `src/${variant}/java`, ...stagedRoots(variant).map(item => item.input)];
  return [...new Set(directories.flatMap(directory => kotlinFiles(join(app, directory))))];
}

const contracts = [
  { name: 'DeveloperToolsContent', package: 'pl.bargor.thesaurus', path: 'pl/bargor/thesaurus/DeveloperToolsContent.kt' },
  { name: 'SignInContent', package: 'pl.bargor.thesaurus', path: 'pl/bargor/thesaurus/SignInContent.kt' },
  { name: 'AuthModule', package: 'pl.bargor.thesaurus.di', path: 'pl/bargor/thesaurus/di/AuthModule.kt' },
  { name: 'FirebaseBackend', package: 'pl.bargor.thesaurus.di', path: 'pl/bargor/thesaurus/di/FirebaseBackend.kt' },
];

function selectedContract(variant, contract) {
  const declaration = new RegExp(`^(?:(?:internal|public|private|abstract|open|data|sealed|tailrec|suspend)\\s+)*(?:class|fun)\\s+${contract.name}\\b`, 'm');
  const files = selectedFiles(variant).filter(path => {
    const source = readFileSync(path, 'utf8');
    return source.match(/^package\s+(\S+)/m)?.[1] === contract.package && declaration.test(source);
  });
  assert.equal(files.length, 1, `${variant} must compile exactly one ${contract.package}.${contract.name}: ${files.join(', ')}`);
  return { path: files[0], source: readFileSync(files[0], 'utf8') };
}

test('canonical inputs stage into distinct generated roots per production variant and test component', () => {
  assert.deepEqual(productionVariants, ['debug', 'release']);
  assert.equal(productionInput, 'src/productionShared/java');
  assert.deepEqual(fixtureInputs, [
    { kind: 'hostTests', input: 'src/sharedTest/java' },
    { kind: 'deviceTests', input: 'src/sharedTest/java' },
  ]);
  assert.equal([...gradle.matchAll(/src\/productionShared\/java/g)].length, 1);
  assert.equal([...gradle.matchAll(/src\/sharedTest\/java/g)].length, 2);
  assert.doesNotMatch(gradle, /(?:srcDir|addStaticSourceDirectory|directories\s*\+=)/,
    'Canonical inputs must not be shared Android Studio content roots');
  assert.match(gradle, /@CacheableTask\s+abstract class StageKotlinSources/);
  assert.match(gradle, /@get:InputDirectory\s+@get:PathSensitive\(PathSensitivity\.RELATIVE\)/);
  assert.match(gradle, /@get:OutputDirectory\s+abstract val outputDirectory: DirectoryProperty/);
  assert.match(gradle, /fileSystemOperations\.sync\s*\{\s*from\(inputDirectory\)\s+into\(outputDirectory\)/);
  assert.match(gradle, /tasks\.register<StageKotlinSources>\(component\.computeTaskName\("stage", "SharedSources"\)\)/);
  assert.match(gradle, /inputDirectory\.set\(layout\.projectDirectory\.dir\(canonicalDirectory\)\)/);
  assert.match(gradle, /component\.sources\.kotlin\)\.addGeneratedSourceDirectory\(stage, StageKotlinSources::outputDirectory\)/);
  assert.doesNotMatch(gradle, /sources\.java.*addGeneratedSourceDirectory/,
    'Built-in Kotlin must register generated Kotlin with Sources.kotlin');
  const roots = ['debug', 'release', 'devDebug'].flatMap(variant => [
    ...stagedRoots(variant),
    ...fixtureInputs.map(item => generatedRoot(`${variant}${item.kind === 'hostTests' ? 'UnitTest' : 'AndroidTest'}`, item.input)),
  ]);
  assert.equal(new Set(roots.map(item => item.producer)).size, roots.length,
    'Components must never share a staging producer/output directory');
  assert.deepEqual(stagedRoots('devDebug'), []);
  assert.ok(roots.filter(item => item.input === 'src/sharedTest/java').every(item => /Test$/.test(item.owner)));
});

test('each variant has exactly one implementation of every variant API with no main shadow', () => {
  for (const variant of ['debug', 'release', 'devDebug']) {
    for (const contract of contracts) {
      const selected = selectedContract(variant, contract);
      const expected = variant === 'devDebug' ? 'devDebug' : 'productionShared';
      assert.equal(selected.path, join(app, `src/${expected}/java`, contract.path));
      for (const obsolete of ['main', 'debug', 'release']) {
        assert.equal(existsSync(join(app, `src/${obsolete}/java`, contract.path)), false,
          `${obsolete} must not duplicate ${contract.name}`);
      }
    }
  }
});

test('production variants retain Google auth and default SDK backend without developer controls', () => {
  for (const variant of ['debug', 'release']) {
    const auth = selectedContract(variant, contracts[2]).source;
    assert.match(auth, /@Module\s+@InstallIn\(SingletonComponent::class\)/);
    assert.match(auth, /@Binds\s+@Singleton\s+abstract fun bindAuthRepository\(repository: FirebaseGoogleAuthRepository\): AuthRepository/);
    const backend = selectedContract(variant, contracts[3]).source;
    assert.match(backend, /class FirebaseBackend @Inject constructor\(\)/);
    assert.match(backend, /fun auth\(\): FirebaseAuth = FirebaseAuthFactory\.create\(\)/);
    assert.match(backend, /fun firestore\(\): FirebaseFirestore = FirebaseFirestoreFactory\.create\(\)/);
    assert.doesNotMatch(backend, /emulatorHost|connectToLocalEmulator|FirebaseOptions|FirebaseApp\.initializeApp/);
    const signIn = selectedContract(variant, contracts[1]).source;
    assert.match(signIn, /GoogleSignInContent\(context, onSignIn\)/);
    assert.doesNotMatch(signIn, /dev-email|dev-password|dev-sign-in|onEmailSignIn\(email, password\)/);
    const tools = selectedContract(variant, contracts[0]).source;
    assert.match(tools, /fun DeveloperToolsContent\(onSignOut: \(\) -> Unit\) = Unit/);
    assert.doesNotMatch(tools, /dev-sign-out|TextButton/);
    for (const path of selectedFiles(variant)) {
      assert.doesNotMatch(readFileSync(path, 'utf8'), /DevEmailAuthRepository|fake-api-key-for-emulator-only|dev-password-123/,
        `${variant} must not compile developer authentication: ${path}`);
    }
  }
});

test('devDebug retains the named demo emulator backend, email sign-in and sign-out controls', () => {
  const auth = selectedContract('devDebug', contracts[2]).source;
  assert.match(auth, /bindAuthRepository\(repository: DevEmailAuthRepository\): AuthRepository/);
  assert.doesNotMatch(auth, /FirebaseGoogleAuthRepository/);
  const backend = selectedContract('devDebug', contracts[3]).source;
  assert.match(backend, /\.setProjectId\("demo-thesaurus"\)/);
  assert.match(backend, /FirebaseAuthFactory\.create\(app\)/);
  assert.match(backend, /FirebaseAuthFactory\.connectToLocalEmulator\(it\)/);
  assert.match(backend, /FirebaseFirestoreFactory\.create\(app, emulatorHost = "10\.0\.2\.2"\)/);
  assert.doesNotMatch(backend, /FirebaseAuthFactory\.create\(\)|FirebaseFirestoreFactory\.create\(\)/);
  assert.match(selectedContract('devDebug', contracts[1]).source, /onEmailSignIn\(email, password\)/);
  assert.match(selectedContract('devDebug', contracts[0]).source, /testTag\("dev-sign-out"\), onClick = onSignOut/);
});
