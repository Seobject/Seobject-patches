const { readFile, writeFile } = require("node:fs/promises");

const repository = "Seobject/Seobject-patches";

module.exports = {
  async prepare(_pluginConfig, { nextRelease, logger }) {
    const version = nextRelease.version;
    const tag = `v${version}`;
    const filename = `patches-${version}.mpp`;
    const createdAt = new Date().toISOString().replace(/\.\d{3}Z$/, "");

    const manifest = {
      created_at: createdAt,
      description:
        (nextRelease.notes || "").trim() ||
        "Pin Playlists patch bundle for YouTube Music.",
      download_url: `https://github.com/${repository}/releases/download/${tag}/${filename}`,
      signature_download_url: "N/A",
      version: tag,
    };

    await writeFile(
      "patch-bundle.json",
      `${JSON.stringify(manifest, null, 2)}\n`,
      "utf8",
    );

    const patchList = JSON.parse(await readFile("patches-list.json", "utf8"));
    patchList.version = version;
    await writeFile(
      "patches-list.json",
      `${JSON.stringify(patchList, null, 2)}\n`,
      "utf8",
    );

    logger.log(`Updated patch bundle metadata for ${tag}`);
  },
};
