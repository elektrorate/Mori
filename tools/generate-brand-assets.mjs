import { copyFile, mkdir, readFile, readdir } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import sharp from "sharp";

const root = fileURLToPath(new URL("../", import.meta.url));
const source = join(root, "assets/host-ia-logo.svg");
const svg = await readFile(source);
const generated = spawnSync(process.execPath, [
  join(root, "node_modules/@tauri-apps/cli/tauri.js"), "icon", source,
], { cwd: root, stdio: "inherit" });
if (generated.status !== 0) throw new Error("Native icon generation failed");

for (const directory of ["public/assets", "landing/assets"]) {
  const destination = join(root, directory);
  await mkdir(destination, { recursive: true });
  await copyFile(source, join(destination, "logo.svg"));
  await copyFile(join(root, "src-tauri/icons/icon.ico"), join(destination, "favicon.ico"));
  for (const [name, size] of [["icon.png", 512], ["favicon-16x16.png", 16], ["favicon-32x32.png", 32], ["apple-touch-icon.png", 180]]) {
    await sharp(svg).resize(size, size).png().toFile(join(destination, name));
  }
}
await sharp(svg).resize(512, 512).png().toFile(join(root, "assets/icon.png"));
await sharp(svg).resize(512, 512).png().toFile(join(root, "landing/assets/logo.png"));

// Keep the Capacitor projects' real resources in sync, not only Tauri's generated copies.
const android = join(root, "android/app/src/main/res");
for (const entry of await readdir(android, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue;
  const directory = join(android, entry.name);
  if (/^mipmap-(l|m|h|xh|xxh|xxxh)dpi$/.test(entry.name)) {
    for (const file of await readdir(directory)) {
      if (!/^ic_launcher(?:_foreground|_background|_round)?\.png$/.test(file)) continue;
      const destination = join(directory, file);
      const { width, height } = await sharp(destination).metadata();
      if (file === "ic_launcher_background.png") {
        await sharp({ create: { width, height, channels: 4, background: "#fff" } }).png().toFile(destination);
      } else {
        await sharp(svg).resize(width, height).png().toFile(destination);
      }
    }
  }
  if (entry.name.startsWith("drawable") && (await readdir(directory)).includes("splash.png")) {
    await splash(join(directory, "splash.png"), entry.name.includes("night"));
  }
}

const ios = join(root, "ios/App/App/Assets.xcassets");
await sharp(svg).resize(1024, 1024).png().toFile(join(ios, "AppIcon.appiconset/AppIcon-512@2x.png"));
for (const file of await readdir(join(ios, "Splash.imageset"))) {
  if (file.endsWith(".png")) await splash(join(ios, "Splash.imageset", file), file.includes("dark"));
}

async function splash(destination, dark) {
  const { width, height } = await sharp(destination).metadata();
  const side = Math.max(32, Math.round(Math.min(width, height) * 0.2));
  const logo = await sharp(svg).resize(side, side).png().toBuffer();
  await sharp({ create: { width, height, channels: 4, background: dark ? "#151515" : "#fff" } })
    .composite([{ input: logo, left: Math.floor((width - side) / 2), top: Math.floor((height - side) / 2) }])
    .png().toFile(destination);
}

console.log("Host-ia logo, favicons, native icons and launch screens generated.");
