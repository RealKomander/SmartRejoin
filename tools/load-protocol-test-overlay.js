// Test-only support for pinned upstream data that is not yet in the npm release.
// Changes module objects in this process; never edits installed dependencies.
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const root = path.resolve(process.env.MC_DATA_OVERLAY);
const mdRoot = path.dirname(require.resolve(process.env.MC_DATA_MODULE));
const registry = require(path.join(mdRoot, 'data.js'));
const paths = require(path.join(root, 'data/dataPaths.json'));
for (const version of ['26.2', '26.3']) {
  const entry = {};
  for (const [field, directory] of Object.entries(paths.pc[version])) {
    let file = path.join(root, 'data', directory, field === 'proto' ? 'proto.yml' : `${field}.json`);
    if (!fs.existsSync(file)) file = path.join(mdRoot, 'minecraft-data/data', directory, `${field}.json`);
    assert(fs.existsSync(file), `Missing overlay data: ${version}/${field}`);
    if (field === 'proto') entry.proto = file;
    else Object.defineProperty(entry, field, { enumerable: true, get: () => require(file) });
  }
  registry.pc[version] = entry;
}
const features = require(path.join(mdRoot, 'minecraft-data/data/pc/common/features.json'));
features.splice(0, features.length, ...require(path.join(root, 'data/pc/common/features.json')));
