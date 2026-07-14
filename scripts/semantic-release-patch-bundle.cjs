const { readFile, writeFile } = require("node:fs/promises");

const repository = "Seobject/Seobject-patches";

module.exports = {
  async prepare(_pluginConfig, { branch, nextRelease, logger }) {
    const version = nextRelease.version;
    const tag = `v${version}`;
    const filename = `patches-${version}.mpp`;
    const createdAt = new Date().toISOString().replace(/\.\d{3}Z$/, "");

    const manifest = {
      created_at: createdAt,
      description:
        (nextRelease.notes || "").trim() ||
        "Random QoL Patches",
      download_url: `https://github.com/${repository}/releases/download/${tag}/${filename}`,
      signature_download_url: "N/A",
      version: tag,
    };

    const channel = branch.name === "main" ? "stable" : "dev";
    const manifestFiles = [
      "patch-bundle.json",
      `seobjects-random-patches-${channel}.json`,
    ];

    // The main-branch canonical URL is also the permanent combined feed.
    // A post-release workflow step updates it after dev releases.
    if (channel === "stable") {
      manifestFiles.push("seobjects-random-patches.json");
    }

    const serializedManifest = `${JSON.stringify(manifest, null, 2)}\n`;
    await Promise.all(
      manifestFiles.map((file) =>
        writeFile(file, serializedManifest, "utf8"),
      ),
    );

    const patchList = JSON.parse(await readFile("patches-list.json", "utf8"));
    patchList.version = version;
    await writeFile(
      "patches-list.json",
      `${JSON.stringify(patchList, null, 2)}\n`,
      "utf8",
    );

    logger.log(`Updated ${channel} patch bundle metadata for ${tag}`);
  },
};
