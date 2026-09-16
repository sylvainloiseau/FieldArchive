// electron/after-pack.js
// Ad-hoc code signing of the macOS bundle, run by electron-builder after packing.
//
// Why this exists: electron-builder 24 leaves the .app *completely* unsigned when no
// Developer ID is available — which is the CI case, since .github/workflows/release.yml
// passes no signing credentials. Renaming the Electron binary to FieldArchive drops the
// linker-signed ad-hoc signature the prebuilt binary shipped with, and nothing signs it
// back ("skipped macOS application code signing"). Downloading the .dmg sets the
// com.apple.quarantine attribute, and Gatekeeper rejects a quarantined bundle with no
// usable signature as « FieldArchive » est endommagé et ne peut pas être ouvert — which
// right-click → Ouvrir cannot bypass.
//
// An ad-hoc signature clears that *damaged* rejection, but it does not make the app
// *trusted*: a quarantined download then hits the notarization gate instead, « Apple n'a
// pas pu confirmer que "FieldArchive" ne contenait pas de logiciel malveillant… ». Users
// clear that one themselves, once, either through System Settings → Privacy & Security →
// Ouvrir quand même / Open Anyway, or with `xattr -dr com.apple.quarantine`. Note that
// right-click → Ouvrir no longer bypasses Gatekeeper since macOS 15 (Sequoia), where Apple
// removed the Control-click override. Removing the prompt entirely — i.e. actually
// notarizing — requires a paid Developer ID certificate. See the "First launch on macOS"
// section of README.md for the user-facing instructions.
//
// electron-builder runs afterPack *before* its own signing step (app-builder-lib
// platformPackager.js: afterPack at :232, doSignAfterPack at :238). So if a real
// certificate is configured later, electron-builder's signature simply overwrites the
// ad-hoc one produced here and this hook needs no change.

const { execFileSync } = require('child_process');
const path = require('path');

exports.default = async function afterPack(context) {
  const { electronPlatformName, appOutDir, packager } = context;

  if (electronPlatformName !== 'darwin' && electronPlatformName !== 'mas') {
    return;
  }

  // codesign exists only on macOS: a build from another host must not fail here.
  if (process.platform !== 'darwin') {
    console.log('  • skipped ad-hoc signing  reason=codesign is only available on macOS');
    return;
  }

  const appPath = path.join(appOutDir, `${packager.appInfo.productFilename}.app`);

  console.log(`  • ad-hoc signing  app=${appPath}`);

  // --deep is deprecated for *distribution* signing, but remains the practical way to
  // ad-hoc sign the nested helper apps and frameworks in a single pass.
  execFileSync('codesign', ['--force', '--deep', '--sign', '-', appPath], {
    stdio: 'inherit',
  });

  // Verify rather than trust: a throw here fails the build, which is far better than
  // publishing another .dmg that cannot be opened.
  execFileSync('codesign', ['--verify', '--deep', '--strict', appPath], {
    stdio: 'inherit',
  });

  console.log('  • ad-hoc signature verified');
};
