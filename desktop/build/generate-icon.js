/**
 * icon.ico 生成器（postinstall 自动执行）
 * --------------------------------------
 * 已存在 icon.ico 时直接跳过；缺失时用纯 Node 程序化生成
 * 256×256 图标（PNG-in-ICO，无需任何图像依赖）：
 * 蓝色渐变底 + 白色价格上升折线。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const OUT = path.join(__dirname, 'icon.ico');
if (fs.existsSync(OUT)) {
  console.log('[icon] icon.ico 已存在，跳过生成');
  process.exit(0);
}

const SIZE = 256;
const px = Buffer.alloc(SIZE * SIZE * 4);

/* ── 背景：垂直渐变（PriceLens 蓝） ── */
const TOP = [86, 168, 255];
const BOTTOM = [37, 99, 235];

/* ── 价格折线：3 段上升 + 端点圆 ── */
const PTS = [[42, 186], [98, 128], [144, 156], [214, 74]];
const LINE_R = 11;
const DOT_R = 15;

function distToSeg(x, y, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((x - ax) * dx + (y - ay) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  const px_ = ax + t * dx, py_ = ay + t * dy;
  return Math.hypot(x - px_, y - py_);
}

for (let y = 0; y < SIZE; y++) {
  const t = y / (SIZE - 1);
  const bg = [
    Math.round(TOP[0] + (BOTTOM[0] - TOP[0]) * t),
    Math.round(TOP[1] + (BOTTOM[1] - TOP[1]) * t),
    Math.round(TOP[2] + (BOTTOM[2] - TOP[2]) * t),
  ];
  for (let x = 0; x < SIZE; x++) {
    let white = false;
    for (let i = 0; i < PTS.length - 1; i++) {
      if (distToSeg(x, y, PTS[i][0], PTS[i][1], PTS[i + 1][0], PTS[i + 1][1]) <= LINE_R) {
        white = true;
        break;
      }
    }
    const last = PTS[PTS.length - 1];
    if (!white && Math.hypot(x - last[0], y - last[1]) <= DOT_R) white = true;

    const o = (y * SIZE + x) * 4;
    if (white) {
      px[o] = 255; px[o + 1] = 255; px[o + 2] = 255;
    } else {
      px[o] = bg[0]; px[o + 1] = bg[1]; px[o + 2] = bg[2];
    }
    px[o + 3] = 255;
  }
}

/* ── PNG 编码（RGBA8，filter 0，zlib IDAT） ── */
let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      crcTable[n] = c >>> 0;
    }
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) crc = crcTable[(crc ^ buf[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(SIZE, 0);
ihdr.writeUInt32BE(SIZE, 4);
ihdr[8] = 8;   // bit depth
ihdr[9] = 6;   // RGBA
const raw = Buffer.alloc(SIZE * (SIZE * 4 + 1));
for (let y = 0; y < SIZE; y++) {
  raw[y * (SIZE * 4 + 1)] = 0; // filter: none
  px.copy(raw, y * (SIZE * 4 + 1) + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
]);

/* ── ICO 容器（单张 256×256，PNG 压缩，Windows Vista+ 支持） ── */
const header = Buffer.alloc(22);
header.writeUInt16LE(0, 0);           // reserved
header.writeUInt16LE(1, 2);           // type: icon
header.writeUInt16LE(1, 4);           // count
header[6] = 0;                        // width 0 = 256
header[7] = 0;                        // height 0 = 256
header.writeUInt16LE(1, 10);          // planes
header.writeUInt16LE(32, 12);         // bpp
header.writeUInt32LE(png.length, 14); // image size
header.writeUInt32LE(22, 18);         // offset
fs.writeFileSync(OUT, Buffer.concat([header, png]));
console.log(`[icon] 已生成 ${OUT}（${header.length + png.length} bytes）`);
