// Audit a GLB: list images (mime, byte size, pixel dims) and materials.
const fs = require('fs');
const path = process.argv[2];
const buf = fs.readFileSync(path);
const jsonLen = buf.readUInt32LE(12);
const json = JSON.parse(buf.slice(20, 20 + jsonLen).toString('utf8'));
// find BIN chunk
let off = 20 + jsonLen;
let bin = null;
while (off < buf.length) {
  const len = buf.readUInt32LE(off);
  const type = buf.readUInt32LE(off + 4);
  if (type === 0x004e4942) { bin = buf.slice(off + 8, off + 8 + len); break; }
  off += 8 + len;
}
function pngDims(b) { return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) }; }
function jpgDims(b) {
  let i = 2;
  while (i < b.length) {
    if (b[i] !== 0xFF) { i++; continue; }
    const m = b[i + 1];
    if (m >= 0xC0 && m <= 0xCF && m !== 0xC4 && m !== 0xC8 && m !== 0xCC)
      return { h: b.readUInt16BE(i + 5), w: b.readUInt16BE(i + 7) };
    i += 2 + b.readUInt16BE(i + 2);
  }
  return { w: 0, h: 0 };
}
let totalBytes = 0, totalDecoded = 0;
(json.images || []).forEach((img, i) => {
  const bv = json.bufferViews[img.bufferView];
  const data = bin.slice(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength);
  const d = img.mimeType === 'image/png' ? pngDims(data) : jpgDims(data);
  const decodedMB = (d.w * d.h * 4 * 1.33) / 1048576; // RGBA + mips
  totalBytes += bv.byteLength; totalDecoded += decodedMB;
  console.log(`img[${i}] ${img.mimeType} ${(bv.byteLength/1048576).toFixed(2)}MB  ${d.w}x${d.h}  ~${decodedMB.toFixed(0)}MB GPU  name=${img.name || ''}`);
});
console.log(`TOTAL: ${(totalBytes/1048576).toFixed(1)}MB packed, ~${totalDecoded.toFixed(0)}MB decoded on GPU`);
(json.materials || []).forEach((m, i) => {
  const t = [];
  const pbr = m.pbrMetallicRoughness || {};
  if (pbr.baseColorTexture) t.push('base=' + pbr.baseColorTexture.index);
  if (pbr.metallicRoughnessTexture) t.push('mr=' + pbr.metallicRoughnessTexture.index);
  if (m.normalTexture) t.push('normal=' + m.normalTexture.index);
  if (m.emissiveTexture) t.push('emissive=' + m.emissiveTexture.index);
  if (m.occlusionTexture) t.push('ao=' + m.occlusionTexture.index);
  console.log(`mat[${i}] ${m.name || ''}: ${t.join(' ') || 'no textures'}`);
});
const texToImg = (json.textures || []).map(t => t.source);
console.log('texture->image map:', JSON.stringify(texToImg));
