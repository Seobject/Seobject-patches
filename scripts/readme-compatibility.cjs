const {
  readFile,
  writeFile,
} = require("node:fs/promises");
const { join } = require("node:path");

const compatibilityRelativePath = join(
  "patches",
  "src",
  "main",
  "kotlin",
  "app",
  "seobject",
  "patches",
  "music",
  "Compatibility.kt",
);

function compareVersionParts(left, right) {
  const leftParts = left.split(".").map(Number);
  const rightParts = right.split(".").map(Number);
  const length = Math.max(leftParts.length, rightParts.length);

  for (let index = 0; index < length; index += 1) {
    const leftPart = leftParts[index] || 0;
    const rightPart = rightParts[index] || 0;

    if (leftPart !== rightPart) {
      return leftPart - rightPart;
    }
  }

  return 0;
}

function readSupportedVersions(compatibilitySource) {
  const versions = [];
  const pattern =
    /AppTarget\s*\(\s*version\s*=\s*"([^"]+)"/g;

  for (
    let match = pattern.exec(compatibilitySource);
    match;
    match = pattern.exec(compatibilitySource)
  ) {
    versions.push(match[1]);
  }

  const uniqueVersions = [...new Set(versions)]
    .sort(compareVersionParts);

  if (uniqueVersions.length === 0) {
    throw new Error(
      "No AppTarget versions were found in Compatibility.kt",
    );
  }

  return uniqueVersions;
}

function formatVersionSentence(versions) {
  if (versions.length === 1) {
    return versions[0];
  }

  if (versions.length === 2) {
    return `${versions[0]} and ${versions[1]}`;
  }

  return (
    `${versions.slice(0, -1).join(", ")}, and ` +
    versions[versions.length - 1]
  );
}

function replaceSupportedVersionTable(readme, versions) {
  const startMarker = "<!-- PATCHES_START -->";
  const endMarker = "<!-- PATCHES_END -->";
  const start = readme.indexOf(startMarker);
  const end = readme.indexOf(endMarker);

  if (start < 0 || end < 0 || end <= start) {
    throw new Error(
      "README patch-list markers are missing or out of order",
    );
  }

  const before = readme.slice(
    0,
    start + startMarker.length,
  );
  const block = readme.slice(
    start + startMarker.length,
    end,
  );
  const after = readme.slice(end);
  const tablePattern =
    /(\*\*[^\n]*Supported versions:\*\*\n\n)\|[^\n]+\|\n\|[^\n]+\|/g;
  const matches = [...block.matchAll(tablePattern)];

  if (matches.length !== 1) {
    throw new Error(
      "Expected exactly one supported-version table inside " +
      `the README patch block; found ${matches.length}`,
    );
  }

  const header =
    `| ${versions.join(" | ")} |`;
  const alignment =
    `| ${versions.map(() => ":---:").join(" | ")} |`;
  const updatedBlock = block.replace(
    tablePattern,
    `$1${header}\n${alignment}`,
  );

  return `${before}${updatedBlock}${after}`;
}

function replaceDevChannelSentence(readme, versions) {
  const pattern =
    /^The stable `main` channel supports YouTube Music (.+?)\. This `dev` channel provides experimental support for .+$/m;
  const matches = [...readme.matchAll(
    new RegExp(pattern.source, "gm"),
  )];

  if (matches.length !== 1) {
    throw new Error(
      "Expected exactly one dev-channel compatibility sentence; " +
      `found ${matches.length}`,
    );
  }

  const stableVersion = matches[0][1];
  const supported = formatVersionSentence(versions);

  return readme.replace(
    pattern,
    "The stable `main` channel supports YouTube Music " +
      `${stableVersion}. This \`dev\` channel provides ` +
      `experimental support for ${supported}.`,
  );
}

async function updateReadmeCompatibility(
  cwd,
  branchName,
) {
  const compatibilityPath = join(
    cwd,
    compatibilityRelativePath,
  );
  const readmePath = join(cwd, "README.md");

  const [
    compatibilitySource,
    originalReadme,
  ] = await Promise.all([
    readFile(compatibilityPath, "utf8"),
    readFile(readmePath, "utf8"),
  ]);

  const versions = readSupportedVersions(
    compatibilitySource,
  );
  let readme = originalReadme.replace(/\r\n?/g, "\n");

  readme = replaceSupportedVersionTable(
    readme,
    versions,
  );

  if (branchName === "dev") {
    readme = replaceDevChannelSentence(
      readme,
      versions,
    );
  } else if (branchName !== "main") {
    throw new Error(
      `Unsupported semantic-release branch: ${branchName}`,
    );
  }

  await writeFile(readmePath, readme, "utf8");
  return versions;
}

module.exports = {
  compareVersionParts,
  formatVersionSentence,
  readSupportedVersions,
  replaceSupportedVersionTable,
  updateReadmeCompatibility,
};

if (require.main === module) {
  const cwd = process.argv[2] || process.cwd();
  const branchName = process.argv[3] || "dev";

  updateReadmeCompatibility(cwd, branchName)
    .then((versions) => {
      process.stdout.write(
        `README compatibility updated: ${versions.join(", ")}\n`,
      );
    })
    .catch((error) => {
      console.error(error);
      process.exitCode = 1;
    });
}
