const repository = process.env.GITHUB_REPOSITORY || "Seobject/Seobject-patches";
const token = process.env.GITHUB_TOKEN;
const targetPath = "seobjects-random-patches.json";

if (!token) {
  throw new Error("GITHUB_TOKEN is required");
}

async function github(path, options = {}) {
  const response = await fetch(`https://api.github.com${path}`, {
    ...options,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "X-GitHub-Api-Version": "2022-11-28",
      ...options.headers,
    },
  });

  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
  }

  return response.status === 204 ? null : response.json();
}

async function main() {
  const releases = await github(`/repos/${repository}/releases?per_page=100`);
  const latest = releases
    .filter((release) => !release.draft && release.published_at)
    .sort((left, right) =>
      Date.parse(right.published_at) - Date.parse(left.published_at),
    )[0];

  if (!latest) {
    console.log("No published release found; combined feed unchanged");
    return;
  }

  const asset = latest.assets.find((candidate) => candidate.name.endsWith(".mpp"));
  if (!asset) {
    throw new Error(`Release ${latest.tag_name} has no .mpp asset`);
  }

  const manifest = {
    created_at: latest.published_at.replace(/\.\d{3}Z$/, ""),
    description: (latest.body || "").trim() || "Random QoL Patches",
    download_url: asset.browser_download_url,
    signature_download_url: "N/A",
    version: latest.tag_name,
  };
  const serialized = `${JSON.stringify(manifest, null, 2)}\n`;
  const current = await github(
    `/repos/${repository}/contents/${targetPath}?ref=main`,
  );
  const currentText = Buffer.from(current.content, "base64").toString("utf8");

  if (currentText === serialized) {
    console.log(`Combined feed already points to ${latest.tag_name}`);
    return;
  }

  await github(`/repos/${repository}/contents/${targetPath}`, {
    method: "PUT",
    body: JSON.stringify({
      message: `chore: update combined release feed to ${latest.tag_name} [skip ci]`,
      content: Buffer.from(serialized).toString("base64"),
      sha: current.sha,
      branch: "main",
    }),
  });

  console.log(`Combined feed now points to ${latest.tag_name}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
