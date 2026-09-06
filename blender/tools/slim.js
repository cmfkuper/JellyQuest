// Slim cinema_imax.glb GPU memory: drop near-black emissive maps, downsize
// normal maps to 1024 and metallic-roughness to 512. Base color untouched.
const { NodeIO } = require('@gltf-transform/core');
const { ALL_EXTENSIONS } = require('@gltf-transform/extensions');
const { prune, textureCompress } = require('@gltf-transform/functions');
const sharp = require('sharp');

const [,, inPath, outPath] = process.argv;

(async () => {
  const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
  const doc = await io.read(inPath);
  const root = doc.getRoot();

  // 1) Remove emissive textures that are effectively black.
  for (const mat of root.listMaterials()) {
    const tex = mat.getEmissiveTexture();
    if (!tex) continue;
    const stats = await sharp(Buffer.from(tex.getImage())).stats();
    const maxMean = Math.max(...stats.channels.slice(0, 3).map(c => c.mean));
    if (maxMean < 8) {
      console.log(`dropping black emissive on ${mat.getName()} (mean=${maxMean.toFixed(1)})`);
      mat.setEmissiveTexture(null);
      mat.setEmissiveFactor([0, 0, 0]);
    } else {
      console.log(`KEEPING emissive on ${mat.getName()} (mean=${maxMean.toFixed(1)})`);
    }
  }

  // 2) Downsize maps that don't need 2K at theater viewing distances.
  await doc.transform(
    textureCompress({ encoder: sharp, resize: [1024, 1024], slots: /normalTexture/ }),
    textureCompress({ encoder: sharp, resize: [512, 512], slots: /metallicRoughnessTexture/ }),
    prune(),
  );

  await io.write(outPath, doc);
  console.log('wrote', outPath);
})().catch(e => { console.error(e); process.exit(1); });
